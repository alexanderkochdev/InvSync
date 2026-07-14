package dev.alexanderkoch.invsync.bukkit.listener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import dev.alexanderkoch.invsync.bukkit.InvSyncBukkit;
import dev.alexanderkoch.invsync.bukkit.sync.InventorySerializer;
import dev.alexanderkoch.invsync.bukkit.sync.SyncRuleManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Handles incoming plugin messages from Velocity.
 * <p>
 * Processes {@code player_data}, {@code player_data_not_found}, and
 * {@code sync_config} messages. Supports chunked payloads for large data.
 */
public class MessageListener implements PluginMessageListener {

    private static final JsonParser PARSER = new JsonParser();

    private final InvSyncBukkit plugin;
    private final SyncRuleManager syncRuleManager;
    private final Logger logger;

    // Chunk assembly buffer
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, JsonObject>> chunkBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> chunkTimeout = new ConcurrentHashMap<>();
    private static final long CHUNK_TIMEOUT_MS = 30_000;

    public MessageListener(InvSyncBukkit plugin, SyncRuleManager syncRuleManager) {
        this.plugin = plugin;
        this.syncRuleManager = syncRuleManager;
        this.logger = plugin.getLogger();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(InvSyncChannel.CHANNEL)) return;

        try {
            JsonObject root = InvSyncChannel.fromBytes(message);
            String type = root.get(InvSyncChannel.KEY_TYPE).getAsString();

            switch (type) {
                case InvSyncChannel.TYPE_SYNC_CONFIG -> handleSyncConfig(root);
                case InvSyncChannel.TYPE_PLAYER_DATA -> handlePlayerData(root);
                case InvSyncChannel.TYPE_PLAYER_DATA_NOT_FOUND -> handlePlayerDataNotFound(root);
                case InvSyncChannel.TYPE_DATA_CHUNK -> handleChunk(root);
                default -> logger.warning("Unknown message type: " + type);
            }
        } catch (Exception e) {
            logger.severe("Failed to parse plugin message: " + e.getMessage());
        }
    }

    // ── Sync Config ────────────────────────────────────────────────

    /** Receive and cache the sync rules for this server group. */
    private void handleSyncConfig(JsonObject root) {
        JsonObject sync = root.getAsJsonObject(InvSyncChannel.KEY_SYNC);
        if (sync == null) {
            logger.warning("Received sync_config without sync rules!");
            return;
        }

        Map<String, Boolean> rules = new HashMap<>();
        rules.put(InvSyncChannel.SYNC_INVENTORY,
                getBoolSafe(sync, InvSyncChannel.SYNC_INVENTORY, true));
        rules.put(InvSyncChannel.SYNC_ENDER_CHEST,
                getBoolSafe(sync, InvSyncChannel.SYNC_ENDER_CHEST, true));
        rules.put(InvSyncChannel.SYNC_HEALTH,
                getBoolSafe(sync, InvSyncChannel.SYNC_HEALTH, true));
        rules.put(InvSyncChannel.SYNC_FOOD,
                getBoolSafe(sync, InvSyncChannel.SYNC_FOOD, true));
        rules.put(InvSyncChannel.SYNC_EXPERIENCE,
                getBoolSafe(sync, InvSyncChannel.SYNC_EXPERIENCE, true));

        syncRuleManager.updateRules(rules);
        logger.info("Received sync config: " + syncRuleManager.getRulesSummary());
    }

    // ── Player Data ────────────────────────────────────────────────

    /** Apply received player data to the target player. */
    private void handlePlayerData(JsonObject root) {
        String uuid = root.get(InvSyncChannel.KEY_UUID).getAsString();
        JsonObject data = root.getAsJsonObject(InvSyncChannel.KEY_DATA);

        if (data == null) {
            logger.warning("Received player_data without data for " + uuid);
            return;
        }

        Player player = Bukkit.getPlayer(UUID.fromString(uuid));
        if (player == null || !player.isOnline()) {
            logger.warning("Player " + uuid + " is no longer online, skipping data apply");
            return;
        }

        // Apply data async-safe (must be on main thread)
        Map<String, Boolean> syncRules = syncRuleManager.getSyncRules();
        Bukkit.getScheduler().runTask(plugin, () -> {
            InventorySerializer.deserializePlayer(player, data, syncRules);
            logger.info("Applied saved data to " + player.getName()
                    + " (inventory=" + syncRules.getOrDefault(InvSyncChannel.SYNC_INVENTORY, true)
                    + ", health=" + syncRules.getOrDefault(InvSyncChannel.SYNC_HEALTH, true)
                    + ", food=" + syncRules.getOrDefault(InvSyncChannel.SYNC_FOOD, true)
                    + ", exp=" + syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true)
                    + ")");
        });
    }

    /** No saved data found — player's first join or data was deleted. */
    private void handlePlayerDataNotFound(JsonObject root) {
        String uuid = root.get(InvSyncChannel.KEY_UUID).getAsString();
        Player player = Bukkit.getPlayer(UUID.fromString(uuid));
        if (player != null && player.isOnline()) {
            logger.info("No saved data for " + player.getName() + " (first join)");
        }
    }

    // ── Chunk Handling ─────────────────────────────────────────────

    /** Accumulate chunk fragments and reassemble when complete. */
    private void handleChunk(JsonObject root) {
        String sessionId = root.get(InvSyncChannel.KEY_CHUNK_SESSION).getAsString();
        int chunkIndex = root.get(InvSyncChannel.KEY_CHUNK_INDEX).getAsInt();
        int totalChunks = root.get(InvSyncChannel.KEY_TOTAL_CHUNKS).getAsInt();

        chunkBuffer.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        chunkBuffer.get(sessionId).put(chunkIndex, root);
        chunkTimeout.put(sessionId, System.currentTimeMillis());

        // Check if all chunks received
        Map<Integer, JsonObject> chunks = chunkBuffer.get(sessionId);
        if (chunks.size() >= totalChunks && totalChunks > 0) {
            chunkBuffer.remove(sessionId);
            chunkTimeout.remove(sessionId);

            // Reassemble
            List<JsonObject> sortedChunks = new ArrayList<>(chunks.values());
            try {
                JsonObject reassembled = InvSyncChannel.reassembleChunks(sortedChunks);
                String type = reassembled.get(InvSyncChannel.KEY_TYPE).getAsString();

                // Re-dispatch
                switch (type) {
                    case InvSyncChannel.TYPE_SYNC_CONFIG -> handleSyncConfig(reassembled);
                    case InvSyncChannel.TYPE_PLAYER_DATA -> handlePlayerData(reassembled);
                    case InvSyncChannel.TYPE_PLAYER_DATA_NOT_FOUND -> handlePlayerDataNotFound(reassembled);
                    default -> logger.warning("Unknown chunked message type: " + type);
                }
            } catch (Exception e) {
                logger.severe("Failed to reassemble chunked message (session=" + sessionId + "): " + e.getMessage());
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private boolean getBoolSafe(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    /** Called periodically to clean up stale chunk buffers. */
    public void cleanStaleChunks() {
        long now = System.currentTimeMillis();
        chunkTimeout.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > CHUNK_TIMEOUT_MS) {
                chunkBuffer.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }
}

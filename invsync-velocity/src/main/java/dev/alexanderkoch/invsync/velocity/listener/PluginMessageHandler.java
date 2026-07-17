package dev.alexanderkoch.invsync.velocity.listener;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import dev.alexanderkoch.invsync.velocity.cache.RedisCacheManager;
import dev.alexanderkoch.invsync.velocity.config.VelocityConfig;
import dev.alexanderkoch.invsync.velocity.database.InventoryRepository;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles incoming plugin messages from Bukkit servers.
 * <p>
 * Processes {@code load_player} and {@code save_player} messages,
 * applies sync rules, integrates with Redis cache, handles chunked messages,
 * and sends responses back to the correct Bukkit server.
 */
public class PluginMessageHandler {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("invsync", "main");

    private final VelocityConfig config;
    private final InventoryRepository inventoryRepository;
    private final RedisCacheManager redisCacheManager;
    private final Logger logger;

    // Chunk assembly buffer: sessionId → (index → chunk)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, JsonObject>> chunkBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> chunkTimeout = new ConcurrentHashMap<>();
    private static final long CHUNK_TIMEOUT_MS = 30_000; // 30 seconds

    private final ProxyServer proxy;

    public PluginMessageHandler(ProxyServer proxy, VelocityConfig config, InventoryRepository inventoryRepository,
                                RedisCacheManager redisCacheManager, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.inventoryRepository = inventoryRepository;
        this.redisCacheManager = redisCacheManager;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        // Verify it's our channel — compare as strings to avoid any ChannelIdentifier
        // type mismatch issues in Velocity 3.x (ResourceLocation vs MinecraftChannelIdentifier)
        String channelName = event.getIdentifier().toString();
        if (!"invsync:main".equals(channelName)) return;

        logger.info("Received plugin message on channel '{}' from source: {} (type: {})",
                channelName, event.getSource(),
                event.getSource().getClass().getName());

        // Mark as handled — prevents forwarding to players
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        // Determine the source server.
        // Bukkit's player.sendPluginMessage() sends through the player tunnel:
        //   Bukkit → Player (via custom_payload) → Velocity
        // In this case event.getSource() is the Player, not the RegisteredServer.
        // We resolve the actual server via player.getCurrentServer().
        RegisteredServer sourceServer = resolveSourceServer(event);
        if (sourceServer == null) return;

        try {
            byte[] data = event.getData();
            JsonObject message = InvSyncChannel.fromBytes(data);
            String type = message.get(InvSyncChannel.KEY_TYPE).getAsString();

            logger.info("Processing message type '{}' from server {} ({} bytes)",
                    type, sourceServer.getServerInfo().getName(), data.length);

            switch (type) {
                case InvSyncChannel.TYPE_LOAD_PLAYER -> handleLoadPlayer(message, sourceServer);
                case InvSyncChannel.TYPE_SAVE_PLAYER -> handleSavePlayer(message, sourceServer);
                case InvSyncChannel.TYPE_DATA_CHUNK -> handleChunk(message, sourceServer);
                default -> logger.warn("Unknown message type from {}: {}", sourceServer.getServerInfo().getName(), type);
            }
        } catch (Exception e) {
            logger.error("Failed to process plugin message from {}: {}",
                    sourceServer.getServerInfo().getName(), e.getMessage());
        }
    }

    /**
     * Resolves the source RegisteredServer from a PluginMessageEvent.
     * <p>
     * When Bukkit calls {@code player.sendPluginMessage()}, the message travels through
     * the player tunnel (Bukkit → Player → Velocity). In Velocity 3.x, the event source
     * is the {@link Player}, not the {@link RegisteredServer}.
     * This method handles both cases:
     * <ul>
     *   <li>Direct source: {@code event.getSource() instanceof RegisteredServer}</li>
     *   <li>Player tunnel: resolves the server via {@code player.getCurrentServer()}</li>
     * </ul>
     */
    private RegisteredServer resolveSourceServer(PluginMessageEvent event) {
        if (event.getSource() instanceof RegisteredServer server) {
            return server;
        }

        if (event.getSource() instanceof Player player) {
            RegisteredServer server = player.getCurrentServer()
                    .map(conn -> conn.getServer())
                    .orElse(null);
            if (server == null) {
                logger.warn("Received message from player {} but they have no current server", player.getUsername());
            } else {
                logger.debug("Resolved source server '{}' via player tunnel for {}",
                        server.getServerInfo().getName(), player.getUsername());
            }
            return server;
        }

        logger.warn("Received plugin message from unknown source type: {} — {}",
                event.getSource().getClass().getName(), event.getSource());
        return null;
    }

    // ── Load Player ────────────────────────────────────────────────

    /**
     * Handle a load_player request from a Bukkit server.
     * Loads player data (from Redis cache + DB), sends sync config + player data back.
     */
    private void handleLoadPlayer(JsonObject message, RegisteredServer sourceServer) {
        String uuid = message.get(InvSyncChannel.KEY_UUID).getAsString();
        String playerName = message.get(InvSyncChannel.KEY_PLAYER_NAME).getAsString();
        String serverName = sourceServer.getServerInfo().getName();

        logger.info("Loading player {} ({}) on server {}", playerName, uuid, serverName);

        // Send sync config first — tells Bukkit what data to apply
        getOptionalPlayer(uuid).ifPresent(player -> {
            Map<String, Boolean> syncRules = config.getSyncRulesForServer(serverName);
            JsonObject syncConfig = buildSyncConfig(syncRules);

            // Send sync_config
            List<JsonObject> syncChunks = InvSyncChannel.chunkIfNeeded(syncConfig,
                    "sync-" + uuid + "-" + System.currentTimeMillis());
            for (JsonObject chunk : syncChunks) {
                player.getCurrentServer().ifPresent(conn ->
                        conn.sendPluginMessage(CHANNEL, InvSyncChannel.toBytes(chunk)));
            }
        });

        // Load player data (Read-Through: Redis → DB)
        InventoryRepository.PlayerData playerData = redisCacheManager.getPlayer(uuid, inventoryRepository);

        if (playerData.isEmpty()) {
            // No saved data — first join
            JsonObject notFound = InvSyncChannel.playerMessage(
                    InvSyncChannel.TYPE_PLAYER_DATA_NOT_FOUND, uuid, playerName);
            getOptionalPlayer(uuid).ifPresent(player ->
                    player.getCurrentServer().ifPresent(conn ->
                            conn.sendPluginMessage(CHANNEL, InvSyncChannel.toBytes(notFound))));
        } else {
            // Send player data
            JsonObject response = InvSyncChannel.playerMessage(
                    InvSyncChannel.TYPE_PLAYER_DATA, uuid, playerName);
            response.add(InvSyncChannel.KEY_DATA, playerData.getData());
            response.addProperty(InvSyncChannel.KEY_DATA_VERSION, playerData.getDataVersion());

            // Chunk if needed
            List<JsonObject> chunks = InvSyncChannel.chunkIfNeeded(response,
                    "data-" + uuid + "-" + System.currentTimeMillis());
            for (JsonObject chunk : chunks) {
                getOptionalPlayer(uuid).ifPresent(player ->
                        player.getCurrentServer().ifPresent(conn ->
                                conn.sendPluginMessage(CHANNEL, InvSyncChannel.toBytes(chunk))));
            }
        }
    }

    // ── Save Player (with Sync-Rule Filtering) ─────────────────────

    /**
     * Handle a save_player request.
     * Sync rules are applied BEFORE writing to DB → prevents cross-group contamination.
     * Uses Redis write-through cache.
     */
    private void handleSavePlayer(JsonObject message, RegisteredServer sourceServer) {
        String uuid = message.get(InvSyncChannel.KEY_UUID).getAsString();
        String playerName = message.get(InvSyncChannel.KEY_PLAYER_NAME).getAsString();
        String serverName = sourceServer.getServerInfo().getName();
        JsonObject data = message.getAsJsonObject(InvSyncChannel.KEY_DATA);

        if (data == null) {
            logger.warn("Save request for {} ({}) from {} has no data", playerName, uuid, serverName);
            return;
        }

        // Get sync rules for this server
        Map<String, Boolean> syncRules = config.getSyncRulesForServer(serverName);

        logger.info("Saving player {} ({}) from server {} (sync rules: inventory={}, ender_chest={}, health={}, food={}, exp={})",
                playerName, uuid, serverName,
                syncRules.getOrDefault(InvSyncChannel.SYNC_INVENTORY, true),
                syncRules.getOrDefault(InvSyncChannel.SYNC_ENDER_CHEST, true),
                syncRules.getOrDefault(InvSyncChannel.SYNC_HEALTH, true),
                syncRules.getOrDefault(InvSyncChannel.SYNC_FOOD, true),
                syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true));

        // Write-Through: Redis first, then DB asynchronously
        Runnable dbSave = redisCacheManager.setPlayer(uuid, playerName, data, syncRules, inventoryRepository);

        // Execute DB save asynchronously
        dbSave.run();
    }

    // ── Chunk Handling ─────────────────────────────────────────────

    /**
     * Handle a chunked message fragment. Accumulates chunks until all are received,
     * then processes the reassembled message.
     */
    private void handleChunk(JsonObject message, RegisteredServer sourceServer) {
        String sessionId = message.get(InvSyncChannel.KEY_CHUNK_SESSION).getAsString();
        int chunkIndex = message.get(InvSyncChannel.KEY_CHUNK_INDEX).getAsInt();
        int totalChunks = message.get(InvSyncChannel.KEY_TOTAL_CHUNKS).getAsInt();

        chunkBuffer.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        chunkBuffer.get(sessionId).put(chunkIndex, message);
        chunkTimeout.put(sessionId, System.currentTimeMillis());

        // Check if all chunks received
        Map<Integer, JsonObject> chunks = chunkBuffer.get(sessionId);
        if (chunks.size() >= totalChunks) {
            // Clean up buffer
            chunkBuffer.remove(sessionId);
            chunkTimeout.remove(sessionId);

            // Reassemble and process
            List<JsonObject> sortedChunks = new ArrayList<>(chunks.values());
            try {
                JsonObject reassembled = InvSyncChannel.reassembleChunks(sortedChunks);
                String type = reassembled.get(InvSyncChannel.KEY_TYPE).getAsString();

                // Re-dispatch to the appropriate handler
                switch (type) {
                    case InvSyncChannel.TYPE_LOAD_PLAYER -> handleLoadPlayer(reassembled, sourceServer);
                    case InvSyncChannel.TYPE_SAVE_PLAYER -> handleSavePlayer(reassembled, sourceServer);
                    default -> logger.warn("Unknown chunked message type: {}", type);
                }
            } catch (Exception e) {
                logger.error("Failed to reassemble chunked message (session={}): {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Clean up stale chunk buffers. Should be called periodically (e.g. from a scheduler).
     */
    public void cleanStaleChunks() {
        long now = System.currentTimeMillis();
        chunkTimeout.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > CHUNK_TIMEOUT_MS) {
                chunkBuffer.remove(entry.getKey());
                logger.warn("Cleaned up stale chunk session: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** Build a sync_config message from sync rules map. */
    private JsonObject buildSyncConfig(Map<String, Boolean> syncRules) {
        JsonObject msg = InvSyncChannel.message(InvSyncChannel.TYPE_SYNC_CONFIG);
        JsonObject sync = new JsonObject();
        sync.addProperty(InvSyncChannel.SYNC_INVENTORY,
                syncRules.getOrDefault(InvSyncChannel.SYNC_INVENTORY, true));
        sync.addProperty(InvSyncChannel.SYNC_ENDER_CHEST,
                syncRules.getOrDefault(InvSyncChannel.SYNC_ENDER_CHEST, true));
        sync.addProperty(InvSyncChannel.SYNC_HEALTH,
                syncRules.getOrDefault(InvSyncChannel.SYNC_HEALTH, true));
        sync.addProperty(InvSyncChannel.SYNC_FOOD,
                syncRules.getOrDefault(InvSyncChannel.SYNC_FOOD, true));
        sync.addProperty(InvSyncChannel.SYNC_EXPERIENCE,
                syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true));
        msg.add(InvSyncChannel.KEY_SYNC, sync);
        return msg;
    }

    /** Get an online player by UUID safely. */
    private Optional<Player> getOptionalPlayer(String uuid) {
        return proxy.getPlayer(UUID.fromString(uuid));
    }
}

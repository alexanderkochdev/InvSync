package dev.alexanderkoch.invsync.bukkit.listener;

import com.google.gson.JsonObject;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import dev.alexanderkoch.invsync.bukkit.InvSyncBukkit;
import dev.alexanderkoch.invsync.bukkit.sync.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Listens for player join/quit/death events and communicates with Velocity
 * to load/save inventory data.
 */
public class PlayerListener implements Listener {

    private final InvSyncBukkit plugin;
    private final Logger logger;
    private final int DATA_TIMEOUT_TICKS = 15 * 20; // 15 seconds

    public PlayerListener(InvSyncBukkit plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * When a player joins the server, request their saved data from Velocity.
     * Delayed slightly to ensure the player is fully initialized.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        logger.info("Player " + player.getName() + " joined — requesting saved data from Velocity");

        // Short delay to ensure the player is fully initialized
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            // Build load_player message using Gson
            JsonObject message = InvSyncChannel.playerMessage(
                    InvSyncChannel.TYPE_LOAD_PLAYER,
                    uuid.toString(),
                    player.getName());

            // Chunk if needed and send
            String sessionId = "load-" + uuid + "-" + System.currentTimeMillis();
            List<JsonObject> chunks = InvSyncChannel.chunkIfNeeded(message, sessionId);
            for (JsonObject chunk : chunks) {
                player.sendPluginMessage(plugin, InvSyncChannel.CHANNEL, InvSyncChannel.toBytes(chunk));
            }
        }, 5); // 5 ticks delay (~250ms)
    }

    /**
     * When a player quits, serialize their data and send it to Velocity.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        savePlayerData(event.getPlayer(), "quit");
    }

    /**
     * When a player dies, save their current data before the death screen.
     * This ensures death doesn't cause old inventory to be loaded on next join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        savePlayerData(event.getEntity(), "death");
    }

    /**
     * Serialize the player's current state and send it to Velocity via plugin messaging.
     * Uses Gson-based JSON construction.
     */
    private void savePlayerData(Player player, String reason) {
        if (!player.isOnline()) return;

        UUID uuid = player.getUniqueId();

        // Serialize player data using the new NBT-based serializer
        JsonObject data = InventorySerializer.serializePlayer(player);

        // Build save_player message
        JsonObject message = InvSyncChannel.playerMessage(
                InvSyncChannel.TYPE_SAVE_PLAYER,
                uuid.toString(),
                player.getName());
        message.add(InvSyncChannel.KEY_DATA, data);

        // Chunk if needed and send
        String sessionId = "save-" + uuid + "-" + System.currentTimeMillis();
        List<JsonObject> chunks = InvSyncChannel.chunkIfNeeded(message, sessionId);
        for (JsonObject chunk : chunks) {
            player.sendPluginMessage(plugin, InvSyncChannel.CHANNEL, InvSyncChannel.toBytes(chunk));
        }

        logger.info("Saved " + player.getName() + " data (" + reason + ")");
    }
}

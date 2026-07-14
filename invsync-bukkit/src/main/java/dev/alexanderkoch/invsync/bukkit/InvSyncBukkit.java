package dev.alexanderkoch.invsync.bukkit;

import dev.alexanderkoch.invsync.api.InvSyncChannel;
import dev.alexanderkoch.invsync.bukkit.listener.MessageListener;
import dev.alexanderkoch.invsync.bukkit.listener.PlayerListener;
import dev.alexanderkoch.invsync.bukkit.sync.SyncRuleManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Main Bukkit plugin class for InvSync.
 * <p>
 * Registers plugin messaging channels, event listeners, and schedules
 * periodic chunk buffer cleanup.
 */
public class InvSyncBukkit extends JavaPlugin {

    private Logger logger;
    private SyncRuleManager syncRuleManager;
    private MessageListener messageListener;
    private ScheduledExecutorService scheduler;

    @Override
    public void onEnable() {
        this.logger = getLogger();
        logger.info("Enabling InvSync-Bukkit v" + getDescription().getVersion() + "...");

        // Initialize sync rule manager (defaults to all-sync until config arrives)
        this.syncRuleManager = new SyncRuleManager();

        // Register plugin messaging channel
        getServer().getMessenger().registerOutgoingPluginChannel(this, InvSyncChannel.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, InvSyncChannel.CHANNEL,
                this.messageListener = new MessageListener(this, syncRuleManager));

        // Register player events
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Schedule periodic chunk buffer cleanup (every 30 seconds)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "InvSync-ChunkCleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> messageListener.cleanStaleChunks(),
                30, 30, TimeUnit.SECONDS);

        logger.info("InvSync-Bukkit enabled. Waiting for sync config from Velocity...");
    }

    @Override
    public void onDisable() {
        logger.info("Disabling InvSync-Bukkit...");

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }

        // Unregister channels
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);

        logger.info("InvSync-Bukkit disabled.");
    }

    /** Get the sync rule manager (used by listeners). */
    public SyncRuleManager getSyncRuleManager() {
        return syncRuleManager;
    }
}

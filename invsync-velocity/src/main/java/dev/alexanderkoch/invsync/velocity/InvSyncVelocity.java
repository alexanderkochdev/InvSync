package dev.alexanderkoch.invsync.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import dev.alexanderkoch.invsync.velocity.cache.RedisCacheManager;
import dev.alexanderkoch.invsync.velocity.command.InvSyncCommand;
import dev.alexanderkoch.invsync.velocity.config.VelocityConfig;
import dev.alexanderkoch.invsync.velocity.database.DatabaseManager;
import dev.alexanderkoch.invsync.velocity.database.InventoryRepository;
import dev.alexanderkoch.invsync.velocity.listener.PluginMessageHandler;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main Velocity plugin class for InvSync.
 * <p>
 * Initializes configuration, database connection pool, Redis cache,
 * inventory repository, plugin message handlers, and admin commands.
 */
@Plugin(
        id = "invsync-velocity",
        name = "InvSync-Velocity",
        version = "1.0.0",
        description = "Central Velocity plugin for inventory synchronization",
        authors = {"Alexander Koch"}
)
public class InvSyncVelocity {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("invsync", "main");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityConfig config;
    private DatabaseManager databaseManager;
    private InventoryRepository inventoryRepository;
    private RedisCacheManager redisCacheManager;
    private PluginMessageHandler pluginMessageHandler;
    private ScheduledExecutorService scheduler;

    @Inject
    public InvSyncVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        // Register channel IMMEDIATELY in constructor — before ProxyInitializeEvent fires.
        // In Velocity 3.5.0, ChannelRegistrar may not propagate channels to backend servers
        // if registration happens after the initial minecraft:register handshake.
        // By registering here (at plugin construction time), Velocity knows about this
        // channel before any backend server connects.
        proxy.getChannelRegistrar().register(CHANNEL);
        logger.info("Registered plugin messaging channel: invsync:main");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("=== InvSync-Velocity initialization step-by-step ===");

        // Step 1: Load config
        logger.info("DIAG: Step 1/7 — Loading config...");
        config = new VelocityConfig(dataDirectory, logger);
        config.load();

        if (!config.isLoaded()) {
            logger.error("DIAG: FAILED — Config not loaded! Channel registration may be incomplete.");
            return;
        }
        logger.info("DIAG: Step 1/7 — Config loaded ({} groups)", config.getGroups().size());

        // Step 2: Initialize database
        logger.info("DIAG: Step 2/7 — Initializing database...");
        databaseManager = new DatabaseManager(config, logger);
        databaseManager.initialize();
        boolean dbHealthy = databaseManager.isHealthy();
        inventoryRepository = new InventoryRepository(databaseManager, logger);
        logger.info("DIAG: Step 2/7 — Database healthy: {}", dbHealthy);

        // Step 3: Initialize Redis cache
        logger.info("DIAG: Step 3/7 — Initializing Redis cache...");
        redisCacheManager = new RedisCacheManager(config, logger);
        redisCacheManager.initialize();
        logger.info("DIAG: Step 3/7 — Redis cache initialized");

        // Step 4: Channel was registered in constructor — verify it's still active
        logger.info("DIAG: Step 4/7 — Channel 'invsync:main' registered in constructor");

        // Step 5: Register message handler
        logger.info("DIAG: Step 5/7 — Creating PluginMessageHandler...");
        pluginMessageHandler = new PluginMessageHandler(
                proxy, config, inventoryRepository, redisCacheManager, logger);
        proxy.getEventManager().register(this, pluginMessageHandler);
        logger.info("DIAG: Step 5/7 — PluginMessageHandler registered");

        // Step 6: Register admin commands
        logger.info("DIAG: Step 6/7 — Registering commands...");
        InvSyncCommand command = new InvSyncCommand(
                proxy, config, databaseManager, inventoryRepository, redisCacheManager, logger);
        proxy.getCommandManager().register("invsync", command, "isync");
        logger.info("DIAG: Step 6/7 — Commands registered");

        // Step 7: Schedule periodic chunk buffer cleanup (every 30 seconds)
        logger.info("DIAG: Step 7/7 — Scheduling chunk cleanup...");
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "InvSync-ChunkCleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> pluginMessageHandler.cleanStaleChunks(),
                30, 30, TimeUnit.SECONDS);
        logger.info("DIAG: Step 7/7 — Cleanup scheduled");

        if (dbHealthy) {
            logger.info("InvSync-Velocity initialized successfully!");
        } else {
            logger.warn("InvSync-Velocity initialized in DEGRADED MODE — database unavailable."
                    + " Inventory sync will not work until the database connection is restored."
                    + " Check the database URL, credentials, and JDBC driver availability.");
        }
        logger.info("Registered {} server groups", config.getGroups().size());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down InvSync-Velocity...");

        // Stop scheduler
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }

        // Close Redis connection
        if (redisCacheManager != null) {
            redisCacheManager.close();
        }

        // Close database pool
        if (databaseManager != null) {
            databaseManager.close();
        }

        logger.info("InvSync-Velocity shut down.");
    }
}

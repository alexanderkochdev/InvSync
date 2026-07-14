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
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Initializing InvSync-Velocity v1.0.0...");

        // 1. Load config
        config = new VelocityConfig(dataDirectory, logger);
        config.load();

        if (!config.isLoaded()) {
            logger.error("Failed to load config.yml — plugin will not function correctly!");
            return;
        }

        // 2. Initialize database
        databaseManager = new DatabaseManager(config, logger);
        databaseManager.initialize();
        inventoryRepository = new InventoryRepository(databaseManager, logger);

        // 3. Initialize Redis cache (optional)
        redisCacheManager = new RedisCacheManager(config, logger);
        redisCacheManager.initialize();

        // 4. Register plugin messaging channel
        proxy.getChannelRegistrar().register(CHANNEL);

        // 5. Register message handler
        pluginMessageHandler = new PluginMessageHandler(
                proxy, config, inventoryRepository, redisCacheManager, logger);
        proxy.getEventManager().register(this, pluginMessageHandler);

        // 6. Register admin commands
        InvSyncCommand command = new InvSyncCommand(
                proxy, config, databaseManager, inventoryRepository, redisCacheManager, logger);
        proxy.getCommandManager().register("invsync", command, "isync");

        // 7. Schedule periodic chunk buffer cleanup (every 30 seconds)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "InvSync-ChunkCleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> pluginMessageHandler.cleanStaleChunks(),
                30, 30, TimeUnit.SECONDS);

        logger.info("InvSync-Velocity initialized successfully!");
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

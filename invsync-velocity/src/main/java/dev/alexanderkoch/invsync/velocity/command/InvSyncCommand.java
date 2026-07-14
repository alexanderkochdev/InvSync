package dev.alexanderkoch.invsync.velocity.command;

import com.google.gson.JsonObject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import dev.alexanderkoch.invsync.velocity.cache.RedisCacheManager;
import dev.alexanderkoch.invsync.velocity.config.VelocityConfig;
import dev.alexanderkoch.invsync.velocity.database.DatabaseManager;
import dev.alexanderkoch.invsync.velocity.database.InventoryRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.*;

/**
 * Admin commands for InvSync on Velocity.
 * <p>
 * Commands:
 * <ul>
 *   <li>{@code /invsync reload} — Reload config from disk</li>
 *   <li>{@code /invsync status} — Show cache & DB status</li>
 *   <li>{@code /invsync sync <player> [server]} — Force-sync a player</li>
 *   <li>{@code /invsync cache clear} — Clear Redis cache</li>
 *   <li>{@code /invsync cache stats} — Show cache stats</li>
 * </ul>
 */
public class InvSyncCommand implements SimpleCommand {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("invsync", "main");

    private static final String PERMISSION_RELOAD = "invsync.admin.reload";
    private static final String PERMISSION_STATUS = "invsync.admin.status";
    private static final String PERMISSION_SYNC = "invsync.admin.sync";
    private static final String PERMISSION_CACHE = "invsync.admin.cache";

    private final ProxyServer proxy;
    private final VelocityConfig config;
    private final DatabaseManager databaseManager;
    private final InventoryRepository inventoryRepository;
    private final RedisCacheManager redisCacheManager;
    private final Logger logger;

    public InvSyncCommand(ProxyServer proxy, VelocityConfig config,
                          DatabaseManager databaseManager, InventoryRepository inventoryRepository,
                          RedisCacheManager redisCacheManager, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.databaseManager = databaseManager;
        this.inventoryRepository = inventoryRepository;
        this.redisCacheManager = redisCacheManager;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendUsage(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(source);
            case "status" -> handleStatus(source);
            case "sync" -> handleSync(source, args);
            case "cache" -> handleCache(source, args);
            default -> sendUsage(source);
        }
    }

    // ── Sub-Commands ───────────────────────────────────────────────

    private void handleReload(CommandSource source) {
        if (!hasPermission(source, PERMISSION_RELOAD, "invsync.admin")) return;

        try {
            config.reload();
            source.sendMessage(Component.text("✅ Config reloaded successfully.", NamedTextColor.GREEN));
            logger.info("Configuration reloaded by {}", source);
        } catch (Exception e) {
            source.sendMessage(Component.text("❌ Failed to reload config: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleStatus(CommandSource source) {
        if (!hasPermission(source, PERMISSION_STATUS, "invsync.admin")) return;

        source.sendMessage(Component.text("── InvSync Status ──", NamedTextColor.AQUA));

        // Config
        source.sendMessage(Component.text("Groups: " + config.getGroups().size(), NamedTextColor.GRAY));

        // Database
        boolean dbHealthy = databaseManager.isHealthy();
        source.sendMessage(Component.text("Database: ")
                .append(Component.text(dbHealthy ? "✅ Connected" : "❌ Disconnected",
                        dbHealthy ? NamedTextColor.GREEN : NamedTextColor.RED)));

        if (dbHealthy) {
            int playerCount = inventoryRepository.getPlayerCount();
            source.sendMessage(Component.text("Stored players: " + playerCount, NamedTextColor.GRAY));
            var poolMXBean = databaseManager.getDataSource().getHikariPoolMXBean();
            source.sendMessage(Component.text("Pool: "
                    + poolMXBean.getActiveConnections() + "/"
                    + poolMXBean.getTotalConnections() + " active",
                    NamedTextColor.GRAY));
        }

        // Redis
        boolean redisAvailable = redisCacheManager.isAvailable();
        source.sendMessage(Component.text("Redis: ")
                .append(Component.text(redisAvailable ? "✅ Connected" : "⏸️ Disabled/Unavailable",
                        redisAvailable ? NamedTextColor.GREEN : NamedTextColor.GRAY)));

        if (config.isRedisEnabled() && !redisAvailable) {
            source.sendMessage(Component.text("  (Redis is configured but not reachable)",
                    NamedTextColor.YELLOW));
        }
    }

    @SuppressWarnings("deprecation")
    private void handleSync(CommandSource source, String[] args) {
        if (!hasPermission(source, PERMISSION_SYNC, "invsync.admin")) return;

        if (args.length < 2) {
            source.sendMessage(Component.text("Usage: /invsync sync <player> [server]", NamedTextColor.RED));
            return;
        }

        String playerName = args[1];
        Optional<Player> targetPlayer = proxy.getPlayer(playerName);

        if (targetPlayer.isEmpty()) {
            source.sendMessage(Component.text("Player '" + playerName + "' is not online.", NamedTextColor.RED));
            return;
        }

        Player player = targetPlayer.get();
        String serverName = (args.length >= 3) ? args[2] : player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("unknown");

        // Invalidate cache for this player
        redisCacheManager.invalidate(player.getUniqueId().toString());

        // Send a load request to the Bukkit server
        JsonObject loadMsg = InvSyncChannel.playerMessage(
                InvSyncChannel.TYPE_LOAD_PLAYER,
                player.getUniqueId().toString(),
                player.getUsername());

        player.getCurrentServer().ifPresent(serverConnection -> {
            byte[] data = InvSyncChannel.toBytes(loadMsg);
            serverConnection.sendPluginMessage(CHANNEL, data);
            source.sendMessage(Component.text(
                    "🔄 Force-sync triggered for " + playerName + " on " + serverName,
                    NamedTextColor.GREEN));
        });

        logger.info("Force-sync for {} triggered by {}", playerName, source);
    }

    private void handleCache(CommandSource source, String[] args) {
        if (!hasPermission(source, PERMISSION_CACHE, "invsync.admin")) return;

        if (args.length < 2) {
            source.sendMessage(Component.text("Usage: /invsync cache clear|stats", NamedTextColor.RED));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "clear" -> {
                redisCacheManager.clearAll();
                source.sendMessage(Component.text("✅ Redis cache cleared.", NamedTextColor.GREEN));
            }
            case "stats" -> {
                boolean available = redisCacheManager.isAvailable();
                source.sendMessage(Component.text("Redis available: "
                        + (available ? "✅" : "❌"), NamedTextColor.GRAY));
                source.sendMessage(Component.text("Configured: "
                        + (config.isRedisEnabled() ? "✅ enabled" : "⏸️ disabled"), NamedTextColor.GRAY));
                if (config.isRedisEnabled()) {
                    source.sendMessage(Component.text("Server: " + config.getRedisHost()
                            + ":" + config.getRedisPort(), NamedTextColor.GRAY));
                }
            }
            default ->
                    source.sendMessage(Component.text("Usage: /invsync cache clear|stats", NamedTextColor.RED));
        }
    }

    // ── Tab Completion ─────────────────────────────────────────────

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 1) {
            return List.of("reload", "status", "sync", "cache");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("cache")) {
            return List.of("clear", "stats");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("sync")) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .toList();
        }

        return List.of();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private boolean hasPermission(CommandSource source, String permission, String fallbackPermission) {
        if (source.hasPermission(permission) || source.hasPermission(fallbackPermission)) {
            return true;
        }
        source.sendMessage(Component.text("❌ You don't have permission to use this command.",
                NamedTextColor.RED));
        return false;
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(Component.text("── InvSync Commands ──", NamedTextColor.AQUA));
        source.sendMessage(Component.text("/invsync reload", NamedTextColor.YELLOW)
                .append(Component.text(" — Reload configuration", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/invsync status", NamedTextColor.YELLOW)
                .append(Component.text(" — Show database & cache status", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/invsync sync <player>", NamedTextColor.YELLOW)
                .append(Component.text(" — Force-sync a player", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("/invsync cache clear|stats", NamedTextColor.YELLOW)
                .append(Component.text(" — Manage Redis cache", NamedTextColor.GRAY)));
    }
}

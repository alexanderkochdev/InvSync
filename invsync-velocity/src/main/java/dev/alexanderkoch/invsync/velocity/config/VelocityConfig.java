package dev.alexanderkoch.invsync.velocity.config;

import com.google.gson.Gson;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages the Velocity-side YAML (HOCON) configuration for InvSync.
 * <p>
 * Controls database credentials, Redis cache settings, server groups with sync rules,
 * and performance settings like chunk size.
 */
public class VelocityConfig {

    private final Path configPath;
    private final Logger logger;
    private final Gson gson = InvSyncChannel.gson();

    private ConfigurationNode root;
    private boolean loaded = false;

    // ── Database Config ────────────────────────────────────────────
    private String dbUrl;
    private String dbHost = "localhost";
    private int dbPort = 3306;
    private String dbName = "invsync";
    private String dbUsername = "minecraft";
    private String dbPassword = "change_me";
    private int dbPoolSize = 10;
    private long dbMaxLifetime = 1800000L;

    // ── Redis Config (optional) ────────────────────────────────────
    private boolean redisEnabled = false;
    private String redisHost = "localhost";
    private int redisPort = 6379;
    private String redisPassword = "";
    private int redisDatabase = 0;
    private int redisCacheTtlSeconds = 300; // 5 min default

    // ── Server Groups ──────────────────────────────────────────────
    private final Map<String, ServerGroup> groups = new LinkedHashMap<>();

    // ── Performance ────────────────────────────────────────────────
    private int maxChunkSize = 48 * 1024;             // 48KB
    private int localCacheMaxSize = 10_000;
    private int localCacheExpireMinutes = 5;

    // ── In-memory server group lookup cache ────────────────────────
    private final Map<String, String> serverToGroup = new ConcurrentHashMap<>();

    // ── Inner Types ────────────────────────────────────────────────

    /** Represents a single server group with its sync rules. */
    public static class ServerGroup {
        private final String name;
        private final List<String> servers;
        private final Map<String, Boolean> sync;

        public ServerGroup(String name, List<String> servers, Map<String, Boolean> sync) {
            this.name = name;
            this.servers = servers;
            this.sync = sync;
        }

        public String getName() { return name; }
        public List<String> getServers() { return servers; }
        public Map<String, Boolean> getSync() { return sync; }

        /** Whether this server group syncs the given feature. */
        public boolean shouldSync(String key) {
            return sync.getOrDefault(key, true);
        }
    }

    // ── Initialization ─────────────────────────────────────────────

    public VelocityConfig(Path dataDirectory, Logger logger) {
        this.configPath = dataDirectory.resolve("config.yml");
        this.logger = logger;
    }

    /** Load (or create default) configuration. */
    public void load() {
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                writeDefaultConfig();
                logger.info("Created default config.yml at {}", configPath);
            }

            ConfigurationLoader<CommentedConfigurationNode> loader =
                    HoconConfigurationLoader.builder().setPath(configPath).build();
            root = loader.load();

            // Database
            dbUrl = getString("database.url", null);
            dbHost = getString("database.host", dbHost);
            dbPort = getInt("database.port", dbPort);
            dbName = getString("database.database", dbName);
            dbUsername = getString("database.username", dbUsername);
            dbPassword = getString("database.password", dbPassword);
            dbPoolSize = getInt("database.pool-size", dbPoolSize);
            dbMaxLifetime = getLong("database.max-lifetime-ms", dbMaxLifetime);

            // Redis
            redisEnabled = getBool("redis.enabled", redisEnabled);
            redisHost = getString("redis.host", redisHost);
            redisPort = getInt("redis.port", redisPort);
            redisPassword = getString("redis.password", redisPassword);
            redisDatabase = getInt("redis.database", redisDatabase);
            redisCacheTtlSeconds = getInt("redis.cache-ttl-seconds", redisCacheTtlSeconds);

            // Performance
            maxChunkSize = getInt("performance.max-chunk-size", maxChunkSize);
            localCacheMaxSize = getInt("performance.local-cache-max-size", localCacheMaxSize);
            localCacheExpireMinutes = getInt("performance.local-cache-expire-minutes", localCacheExpireMinutes);

            // Server groups
            groups.clear();
            serverToGroup.clear();
            ConfigurationNode groupsNode = root.getNode("server_groups");
            if (!groupsNode.isVirtual()) {
                for (ConfigurationNode groupNode : groupsNode.getChildrenList()) {
                    String name = groupNode.getNode("name").getString();
                    List<String> servers = groupNode.getNode("servers").getList(Object::toString);
                    Map<String, Boolean> sync = new HashMap<>();

                    ConfigurationNode syncNode = groupNode.getNode("sync");
                    if (!syncNode.isVirtual()) {
                        sync.put(InvSyncChannel.SYNC_INVENTORY,
                                syncNode.getNode(InvSyncChannel.SYNC_INVENTORY).getBoolean(true));
                        sync.put(InvSyncChannel.SYNC_ENDER_CHEST,
                                syncNode.getNode(InvSyncChannel.SYNC_ENDER_CHEST).getBoolean(true));
                        sync.put(InvSyncChannel.SYNC_HEALTH,
                                syncNode.getNode(InvSyncChannel.SYNC_HEALTH).getBoolean(true));
                        sync.put(InvSyncChannel.SYNC_FOOD,
                                syncNode.getNode(InvSyncChannel.SYNC_FOOD).getBoolean(true));
                        sync.put(InvSyncChannel.SYNC_EXPERIENCE,
                                syncNode.getNode(InvSyncChannel.SYNC_EXPERIENCE).getBoolean(true));
                    } else {
                        sync.putAll(InvSyncChannel.DEFAULT_SYNC_RULES);
                    }

                    if (name != null && servers != null) {
                        ServerGroup group = new ServerGroup(name, servers, Collections.unmodifiableMap(sync));
                        groups.put(name, group);
                        servers.forEach(srv -> serverToGroup.put(srv.toLowerCase(), name));
                    }
                }
            }

            loaded = true;
            logger.info("Loaded {} server groups from config", groups.size());
            if (redisEnabled) {
                logger.info("Redis cache is ENABLED ({}:{})", redisHost, redisPort);
            }

        } catch (Exception e) {
            logger.error("Failed to load config.yml", e);
        }
    }

    /** Reload the configuration from disk at runtime. */
    public void reload() {
        logger.info("Reloading configuration...");
        serverToGroup.clear();
        load();
    }

    // ── Getters ────────────────────────────────────────────────────

    public boolean isLoaded() { return loaded; }

    // Database
    public String getDbHost() { return dbHost; }
    public int getDbPort() { return dbPort; }
    public String getDbName() { return dbName; }
    public String getDbUsername() { return dbUsername; }
    public String getDbPassword() { return dbPassword; }
    public int getDbPoolSize() { return dbPoolSize; }
    public long getDbMaxLifetime() { return dbMaxLifetime; }

    public String getJdbcUrl() {
        if (dbUrl != null && !dbUrl.isEmpty()) {
            return dbUrl;
        }
        return "jdbc:mariadb://" + dbHost + ":" + dbPort + "/" + dbName
                + "?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4";
    }

    /** Returns the configured full JDBC URL override, or null if using host/port/database fields. */
    public String getDbUrl() {
        return dbUrl;
    }

    // Redis
    public boolean isRedisEnabled() { return redisEnabled; }
    public String getRedisHost() { return redisHost; }
    public int getRedisPort() { return redisPort; }
    public String getRedisPassword() { return redisPassword; }
    public int getRedisDatabase() { return redisDatabase; }
    public int getRedisCacheTtlSeconds() { return redisCacheTtlSeconds; }

    // Performance
    public int getMaxChunkSize() { return maxChunkSize; }
    public int getLocalCacheMaxSize() { return localCacheMaxSize; }
    public int getLocalCacheExpireMinutes() { return localCacheExpireMinutes; }

    // Groups
    public Map<String, ServerGroup> getGroups() { return Collections.unmodifiableMap(groups); }

    /** Get the group for a server by its name, or empty if unconfigured. */
    public Optional<ServerGroup> getGroupForServer(String serverName) {
        String groupName = serverToGroup.get(serverName.toLowerCase());
        if (groupName == null) {
            // Fallback: linear scan (for wildcard support)
            for (ServerGroup group : groups.values()) {
                if (group.getServers().stream().anyMatch(s -> s.equalsIgnoreCase(serverName))) {
                    groupName = group.getName();
                    serverToGroup.put(serverName.toLowerCase(), groupName);
                    return Optional.of(group);
                }
            }
            return Optional.empty();
        }
        return Optional.ofNullable(groups.get(groupName));
    }

    /** Get the sync rules for a specific server. */
    public Map<String, Boolean> getSyncRulesForServer(String serverName) {
        return getGroupForServer(serverName)
                .map(ServerGroup::getSync)
                .orElse(InvSyncChannel.DEFAULT_SYNC_RULES);
    }

    // ── Config Node Helpers ────────────────────────────────────────

    private String getString(String path, String def) {
        return root.getNode((Object[]) path.split("\\.")).getString(def);
    }

    private int getInt(String path, int def) {
        return root.getNode((Object[]) path.split("\\.")).getInt(def);
    }

    private long getLong(String path, long def) {
        return root.getNode((Object[]) path.split("\\.")).getLong(def);
    }

    private boolean getBool(String path, boolean def) {
        return root.getNode((Object[]) path.split("\\.")).getBoolean(def);
    }

    // ── Default Config ─────────────────────────────────────────────

    private void writeDefaultConfig() throws IOException {
        String defaultConfig =
                "# ───────────────────────────────────────────────────────\n" +
                "# InvSync Velocity Configuration\n" +
                "# ───────────────────────────────────────────────────────\n" +
                "\n" +
                "database {\n" +
                "  # Full JDBC URL override. If set, overrides host/port/database above.\n" +
                "  # Useful when you need a different protocol like jdbc:mysql://\n" +
                "  # if the MariaDB JDBC driver has shading issues in your environment.\n" +
                "  # Example: url = \"jdbc:mysql://127.0.0.1:3306/shared_inventories\"\n" +
                "  # url = \"jdbc:mariadb://localhost:3306/invsync\"\n" +
                "  host = \"localhost\"\n" +
                "  port = 3306\n" +
                "  database = \"invsync\"\n" +
                "  username = \"minecraft\"\n" +
                "  password = \"change_me\"\n" +
                "  # Connection pool size\n" +
                "  pool-size = 10\n" +
                "  # Maximum connection lifetime (ms, default 30 min)\n" +
                "  max-lifetime-ms = 1800000\n" +
                "}\n" +
                "\n" +
                "# Redis cache (optional). When enabled, player data is cached in Redis\n" +
                "# as a read-through/write-through cache to reduce MariaDB load.\n" +
                "redis {\n" +
                "  enabled = false\n" +
                "  host = \"localhost\"\n" +
                "  port = 6379\n" +
                "  password = \"\"\n" +
                "  database = 0\n" +
                "  # How long cached data lives before being re-fetched from DB\n" +
                "  cache-ttl-seconds = 300\n" +
                "}\n" +
                "\n" +
                "# Performance tuning\n" +
                "performance {\n" +
                "  # Maximum plugin message payload size (bytes) before chunking\n" +
                "  max-chunk-size = 49152\n" +
                "  # Local in-process cache size (used when Redis is disabled)\n" +
                "  local-cache-max-size = 10000\n" +
                "  # Local cache entry expiration in minutes\n" +
                "  local-cache-expire-minutes = 5\n" +
                "}\n" +
                "\n" +
                "# ───────────────────────────────────────────────────────\n" +
                "# Server Groups\n" +
                "# ───────────────────────────────────────────────────────\n" +
                "# Define groups of servers that share inventory data.\n" +
                "# Players moving between servers in the same group keep their inventory.\n" +
                "# Moving to a different group loads that group's saved data.\n" +
                "server_groups = [\n" +
                "  {\n" +
                "    name = \"lobby\"\n" +
                "    servers = [\"lobby1\", \"lobby2\"]\n" +
                "    sync = {\n" +
                "      inventory = true\n" +
                "      ender_chest = true\n" +
                "      health = true\n" +
                "      food = true\n" +
                "      experience = false\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    name = \"survival\"\n" +
                "    servers = [\"survival1\", \"survival2\"]\n" +
                "    sync = {\n" +
                "      inventory = true\n" +
                "      ender_chest = false\n" +
                "      health = true\n" +
                "      food = true\n" +
                "      experience = true\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    name = \"minigame\"\n" +
                "    servers = [\"minigame1\", \"minigame2\"]\n" +
                "    sync = {\n" +
                "      inventory = false\n" +
                "      ender_chest = false\n" +
                "      health = false\n" +
                "      food = false\n" +
                "      experience = false\n" +
                "    }\n" +
                "  }\n" +
                "]\n";

        Files.writeString(configPath, defaultConfig);
    }
}

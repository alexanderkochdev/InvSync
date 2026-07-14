package dev.alexanderkoch.invsync.velocity.cache;

import com.google.gson.JsonObject;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import dev.alexanderkoch.invsync.velocity.config.VelocityConfig;
import dev.alexanderkoch.invsync.velocity.database.InventoryRepository;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.Optional;

/**
 * Optional Redis read-through/write-through cache for player inventory data.
 * <p>
 * Reduces MariaDB load by caching recently accessed player data in Redis.
 * Uses a two-tier approach:
 * <ol>
 *   <li><b>Read-Through:</b> On {@link #getPlayer(String, InventoryRepository)},
 *       checks Redis first, falls back to MariaDB + populates cache.</li>
 *   <li><b>Write-Through:</b> On {@link #setPlayer(String, String, JsonObject, Map, InventoryRepository)},
 *       writes to Redis first, then asynchronously to MariaDB.</li>
 * </ol>
 */
public class RedisCacheManager implements AutoCloseable {

    private static final String KEY_PREFIX = "invsync:player:";
    private static final String VERSION_SUFFIX = ":version";

    private final VelocityConfig config;
    private final Logger logger;
    private JedisPool jedisPool;
    private boolean available = false;

    public RedisCacheManager(VelocityConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /** Initialize the Redis connection pool. No-op if Redis is disabled. */
    public void initialize() {
        if (!config.isRedisEnabled()) {
            logger.info("Redis cache is disabled (configure redis.enabled = true to enable)");
            return;
        }

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(16);
            poolConfig.setMaxIdle(8);
            poolConfig.setMinIdle(2);
            poolConfig.setMaxWait(Duration.ofSeconds(3));
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);

            if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
                jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(),
                        (int) Duration.ofSeconds(5).toMillis(), config.getRedisPassword(), config.getRedisDatabase());
            } else {
                jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(),
                        (int) Duration.ofSeconds(5).toMillis(), null, config.getRedisDatabase());
            }

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                available = true;
                logger.info("Redis cache connected to {}:{} (db {})",
                        config.getRedisHost(), config.getRedisPort(), config.getRedisDatabase());
            }
        } catch (Exception e) {
            logger.warn("Redis cache is configured but unavailable: {}. Falling back to direct DB access.", e.getMessage());
            available = false;
        }
    }

    // ── Read-Through Cache ─────────────────────────────────────────

    /**
     * Get player data from Redis cache. Returns empty if:
     * <ul>
     *   <li>Redis is disabled/unavailable</li>
     *   <li>Player is not cached</li>
     *   <li>Cache entry has expired</li>
     * </ul>
     */
    public Optional<InventoryRepository.PlayerData> getCached(String uuid) {
        if (!available || jedisPool == null) return Optional.empty();

        try (Jedis jedis = jedisPool.getResource()) {
            String key = redisKey(uuid);

            // Check if key exists
            String json = jedis.get(key);
            if (json == null) return Optional.empty();

            String versionStr = jedis.get(key + VERSION_SUFFIX);
            int version = versionStr != null ? Integer.parseInt(versionStr) : 0;

            JsonObject data = InvSyncChannel.parse(json);
            String playerName = data.has(InvSyncChannel.KEY_PLAYER_NAME)
                    ? data.get(InvSyncChannel.KEY_PLAYER_NAME).getAsString() : "";

            // Remove metadata before creating PlayerData
            data.remove(InvSyncChannel.KEY_PLAYER_NAME);

            return Optional.of(new InventoryRepository.PlayerData(uuid, playerName, data, version));
        } catch (Exception e) {
            logger.debug("Redis cache read failed for {}: {}", uuid, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read-Through: Try cache first, then DB, and populate cache on miss.
     */
    public InventoryRepository.PlayerData getPlayer(String uuid, InventoryRepository dbRepo) {
        // Try cache first
        Optional<InventoryRepository.PlayerData> cached = getCached(uuid);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Cache miss — load from DB
        InventoryRepository.PlayerData dbData = dbRepo.loadPlayer(uuid);

        // Populate cache if data exists
        if (!dbData.isEmpty() && available) {
            try (Jedis jedis = jedisPool.getResource()) {
                String key = redisKey(uuid);
                JsonObject cacheData = dbData.getData().deepCopy();
                cacheData.addProperty(InvSyncChannel.KEY_PLAYER_NAME, dbData.getPlayerName());
                jedis.setex(key, config.getRedisCacheTtlSeconds(), InvSyncChannel.gson().toJson(cacheData));
                jedis.setex(key + VERSION_SUFFIX, config.getRedisCacheTtlSeconds(),
                        String.valueOf(dbData.getDataVersion()));
            } catch (Exception e) {
                logger.debug("Failed to populate Redis cache for {}: {}", uuid, e.getMessage());
            }
        }

        return dbData;
    }

    // ── Write-Through Cache ────────────────────────────────────────

    /**
     * Write-Through: Save to Redis cache immediately, then return —
     * DB is written asynchronously by the caller or a separate mechanism.
     * <p>
     * Returns a Runnable that performs the DB save (for async execution).
     */
    public Runnable setPlayer(String uuid, String playerName, JsonObject data,
                               java.util.Map<String, Boolean> syncRules,
                               InventoryRepository dbRepo) {
        // Write to Redis synchronously
        if (available && jedisPool != null) {
            try (Jedis jedis = jedisPool.getResource()) {
                String key = redisKey(uuid);
                JsonObject cacheData = data.deepCopy();
                cacheData.addProperty(InvSyncChannel.KEY_PLAYER_NAME, playerName);
                jedis.setex(key, config.getRedisCacheTtlSeconds(), InvSyncChannel.gson().toJson(cacheData));
            } catch (Exception e) {
                logger.debug("Redis cache write failed for {}: {}", uuid, e.getMessage());
            }
        }

        // Return DB save as Runnable
        return () -> dbRepo.savePlayer(uuid, playerName, data, syncRules);
    }

    // ── Invalidation ───────────────────────────────────────────────

    /** Remove a player from the cache. Used when reloading or force-syncing. */
    public void invalidate(String uuid) {
        if (!available || jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String key = redisKey(uuid);
            jedis.del(key, key + VERSION_SUFFIX);
        } catch (Exception e) {
            logger.debug("Redis cache invalidation failed for {}: {}", uuid, e.getMessage());
        }
    }

    /** Clear all cached player data. */
    public void clearAll() {
        if (!available || jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String[] keys = jedis.keys(KEY_PREFIX + "*").toArray(new String[0]);
            if (keys.length > 0) {
                jedis.del(keys);
            }
        } catch (Exception e) {
            logger.debug("Redis cache clear failed: {}", e.getMessage());
        }
    }

    /** Check if Redis is currently connected. */
    public boolean isAvailable() {
        if (!available || jedisPool == null) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            return true;
        } catch (Exception e) {
            available = false;
            return false;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static String redisKey(String uuid) {
        return KEY_PREFIX + uuid;
    }

    @Override
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            logger.info("Redis connection pool closed");
        }
    }
}

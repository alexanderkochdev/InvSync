# InvSync — AI Context File

## Project Overview

InvSync synchronizes player inventories across multiple Minecraft servers via a Velocity proxy.  
The config lives on **Velocity** (server groups + sync rules), Bukkit servers are lightweight clients.

**Current version**: 1.0.0

## Architecture

### Modules

```
InvSync/  (Multi-Module Maven)
├── invsync-api/          Shared API (channel constants, Gson helpers, chunking)
├── invsync-bukkit/       Bukkit plugin — serialization only, no DB
└── invsync-velocity/     Velocity plugin — config, groups, DB, cache
```

### Module: invsync-api

```
dev.alexanderkoch.invsync.api
└── InvSyncChannel.java    — Channel name, message types, Gson helpers, chunking utils
```

### Module: invsync-bukkit

```
dev.alexanderkoch.invsync.bukkit
├── InvSyncBukkit.java                # Main class (JavaPlugin) + chunk cleanup scheduler
├── listener/
│   ├── MessageListener.java          # Incoming plugin messages from Velocity (Gson + chunking)
│   └── PlayerListener.java           # Player join/quit/death events (Gson messages)
└── sync/
    ├── InventorySerializer.java      # ItemStack ↔ Base64 via NBT (Paper serializeAsBytes)
    └── SyncRuleManager.java          # Cached sync rules from Velocity (thread-safe)
```

### Module: invsync-velocity

```
dev.alexanderkoch.invsync.velocity
├── InvSyncVelocity.java              # Main class (Velocity @Plugin) + init order
│   # Logs "degraded mode" if database fails — plugin continues running without DB
├── config/
│   └── VelocityConfig.java           # HOCON config: DB (optional url override), Redis, Performance, Groups
├── database/
│   ├── DatabaseManager.java          # HikariCP pool + explicit JDBC driver loading + schema creation
│   └── InventoryRepository.java      # CRUD with versioning + sync-rule filtering on save
├── cache/
│   └── RedisCacheManager.java        # Optional Redis read/write-through cache (Jedis)
├── command/
│   └── InvSyncCommand.java           # Admin commands: reload, status, sync, cache
└── listener/
    └── PluginMessageHandler.java     # Plugin messaging: load/save/chunk handling
```

## How It Works

### Data Flow

1. **Player joins Bukkit server** → Bukkit sends `load_player` (Gson JSON) to Velocity
2. **Velocity** looks up server name → finds the group → sends `sync_config` + checks **Redis cache** first, then **MariaDB**
3. **Velocity** sends `player_data` or `player_data_not_found` back to Bukkit (chunked if >48KB)
4. **Bukkit** applies inventory/health/XP to player (filtered by sync rules)
5. **Player quits/dies** → Bukkit serializes items (NBT) → sends `save_player` to Velocity
6. **Velocity** writes to **Redis cache** (synchronously) + **MariaDB** (sync-filtered, version-checked)

### Race-Condition Protection
- Each save increments a `data_version` counter in MariaDB
- UPDATE uses `WHERE data_version = ?` — if another server saved concurrently, the save is discarded
- Redis acts as a read-through cache to reduce DB load

### Chunking
- Payloads > 48KB are split into `data_chunk` messages
- Each chunk has: `chunk_session`, `chunk_index`, `total_chunks`, `chunk_data` (Base64)
- Stale chunk buffers are cleaned every 30 seconds

### Plugin Messaging Protocol

- **Channel**: `invsync:main`
- **Format**: UTF-8 encoded JSON (via Gson, `disableHtmlEscaping`)
- **Max single chunk**: ~48KB (configurable)
- **Chunking**: automatic for payloads exceeding max chunk size

#### Bukkit → Velocity Messages

**load_player** (Player joined a server):
```json
{"type":"load_player","uuid":"550e8400-e29b-41d4-a716-446655440000","player_name":"Alex"}
```

**save_player** (Player quit, kicked, or died):
```json
{
  "type": "save_player",
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "player_name": "Alex",
  "data": {
    "inventory": "BASE64_NBT_ENCODED_ITEMS",
    "ender_chest": "BASE64_NBT_ENCODED_ITEMS",
    "health": "20.0",
    "max_health": "20.0",
    "food": "20",
    "saturation": "5.0",
    "level": "42",
    "exp": "0.5",
    "total_experience": "5000"
  }
}
```

#### Velocity → Bukkit Messages

**player_data** (Response to load_player):
```json
{
  "type": "player_data",
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "data_version": 5,
  "data": {
    "inventory": "BASE64_NBT_ENCODED_ITEMS",
    "ender_chest": "BASE64_NBT_ENCODED_ITEMS",
    "health": "20.0",
    "max_health": "20.0",
    "food": "20",
    "saturation": "5.0",
    "level": "42",
    "exp": "0.5",
    "total_experience": "5000"
  }
}
```

**player_data_not_found** (No saved data — first join):
```json
{"type":"player_data_not_found","uuid":"550e8400-e29b-41d4-a716-446655440000"}
```

**sync_config** (Sync rules for this server group):
```json
{
  "type": "sync_config",
  "sync": {
    "inventory": true,
    "ender_chest": false,
    "health": true,
    "food": true,
    "experience": true
  }
}
```

**data_chunk** (Fragment of a large payload):
```json
{
  "type": "data_chunk",
  "chunk_session": "data-550e8400-1741234567890",
  "chunk_index": 0,
  "total_chunks": 3,
  "chunk_data": "BASE64_ENCODED_FRAGMENT"
}
```

## Conventions

### Naming
- **Classes**: PascalCase (`InventorySerializer`, `RedisCacheManager`)
- **Methods**: camelCase (`shouldSync`, `getPlayer`, `serializePlayer`)
- **Constants**: UPPER_SNAKE_CASE (`TYPE_LOAD_PLAYER`, `MAX_CHUNK_SIZE`)
- **Packages**: lowercase (`database`, `cache`, `command`)
- Java 21, 4-space indentation
- try-with-resources for database operations

### Code Style
- SLF4J logging on Velocity (`Logger` via `@Inject`)
- `java.util.logging.Logger` on Bukkit (via `getLogger()`)
- Bukkit: sync operations on main thread via `Bukkit.getScheduler().runTask()`
- Velocity: all plugin message handling is already async
- Prefer `var` for local variables when type is obvious (Java 21)
- **All JSON uses Gson** — no manual StringBuilder JSON anywhere
- All plugin messages use `InvSyncChannel.gson()` and `InvSyncChannel.toBytes()`

## Dependencies

### invsync-api
- Gson 2.10.1

### invsync-bukkit
- Paper API 1.21-R0.1-SNAPSHOT (provided)
- invsync-api (shaded into bukkit as `*.libs.api.*`)
- Gson 2.10.1 (provided — bundled with Paper)

### invsync-velocity
- Velocity API 3.3.0-SNAPSHOT (provided)
- HikariCP 5.1.0 (shaded → `*.libs.hikari.*`)
- MariaDB JDBC 3.3.3 (shaded → `*.libs.mariadb.*`)
- invsync-api (shaded → `*.libs.api.*`)
- Gson 2.10.1 (shaded → `*.libs.gson.*`)
- Jedis 5.1.3 (shaded → `*.libs.redis.*`) — Redis client
- Caffeine 3.1.8 (shaded → `*.libs.caffeine.*`) — in-process cache

## Build

- Multi-module Maven (`mvn clean package`)
- `maven-shade-plugin` for fat JARs
- All deps relocated to `*.libs.*` to avoid classpath conflicts
- Output: `InvSync-Bukkit-1.0.0.jar` + `InvSync-Velocity-1.0.0.jar`
- GitHub Actions CI on every push

## Installation

### Velocity
1. Copy `InvSync-Velocity-1.0.0.jar` to `velocity/plugins/`
2. Start Velocity → generates `config.yml` in plugin data folder
3. Edit config: database credentials, optional Redis, server groups
4. Restart Velocity

### Bukkit (every backend server)
1. Copy `InvSync-Bukkit-1.0.0.jar` to each server's `plugins/`
2. **No configuration needed** — receives everything from Velocity
3. Restart each server

### Redis (optional)
1. Configure `redis.enabled: true` in Velocity config.yml
2. Point to your Redis server

## Velocity Config (Full)

```hocon
database {
  # Full JDBC URL override. If set, overrides host/port/database above.
  # Useful if the shaded MariaDB driver is not auto-discovered in your
  # Java/plugin-container environment — switch to jdbc:mysql:// with a
  # MySQL Connector/J driver installed on the classpath instead.
  # url = "jdbc:mysql://127.0.0.1:3306/shared_inventories"
  host = "localhost"
  port = 3306
  database = "invsync"
  username = "minecraft"
  password = "change_me"
  pool-size = 10
  max-lifetime-ms = 1800000
}

redis {
  enabled = false
  host = "localhost"
  port = 6379
  password = ""
  database = 0
  cache-ttl-seconds = 300
}

performance {
  max-chunk-size = 49152
  local-cache-max-size = 10000
  local-cache-expire-minutes = 5
}

server_groups = [
  {
    name = "lobby"
    servers = ["lobby1", "lobby2"]
    sync {
      inventory = true
      ender_chest = true
      health = true
      food = true
      experience = false
    }
  },
  {
    name = "survival"
    servers = ["survival1", "survival2"]
    sync {
      inventory = true
      ender_chest = false
      health = true
      food = true
      experience = true
    }
  },
  {
    name = "minigame"
    servers = ["minigame1", "minigame2"]
    sync {
      inventory = false
      ender_chest = false
      health = false
      food = false
      experience = false
    }
  }
]
```

## Database Schema

```sql
CREATE TABLE IF NOT EXISTS invsync_player_inventories (
    uuid VARCHAR(36) PRIMARY KEY,
    player_name VARCHAR(16) NOT NULL,
    inventory LONGTEXT,
    ender_chest LONGTEXT,
    health DOUBLE DEFAULT 20.0,
    max_health DOUBLE DEFAULT 20.0,
    food INT DEFAULT 20,
    saturation FLOAT DEFAULT 5.0,
    level INT DEFAULT 0,
    exp FLOAT DEFAULT 0.0,
    total_experience INT DEFAULT 0,
    data_version INT DEFAULT 0,        -- Race-condition protection
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## Admin Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/invsync reload` | `invsync.admin.reload` | Reload config from disk |
| `/invsync status` | `invsync.admin.status` | Show DB + cache status |
| `/invsync sync <player>` | `invsync.admin.sync` | Force-sync a player |
| `/invsync cache clear` | `invsync.admin.cache` | Clear Redis cache |
| `/invsync cache stats` | `invsync.admin.cache` | Show cache stats |

## Common AI Tasks

### Adding a new sync feature (e.g., "potions")
1. Add key to `InvSyncChannel.java` (e.g., `KEY_POTIONS`, `SYNC_POTIONS`)
2. Add sync rule in `VelocityConfig.java` default sync loading
3. Add field to database schema in `DatabaseManager.java` + `InventoryRepository.java` (load + save with sync filtering)
4. Serialize in `InventorySerializer.java` (Bukkit)
5. Apply in `MessageListener.java` (Bukkit — respect sync rule)
6. Add config default in `config.yml` (Velocity default config)
7. Add field to `RedisCacheManager` set/get if needed

### Adding a new server group
No code changes needed — just edit `config.yml` on Velocity:
```hocon
{
  name = "creative"
  servers = ["creative"]
  sync {
    inventory = true
    ender_chest = true
    health = false
    food = false
    experience = true
  }
}
```

### Debugging sync issues
1. Check Velocity logs for `InvSync-Velocity` — DB errors, missing groups
2. Check Bukkit logs for `InvSync` — serialization errors, connection issues
3. Verify servers are listed in the correct group in `config.yml`
4. Try `/invsync status` on Velocity to check DB/Redis health
5. Try `/invsync sync <player>` to force a sync
6. Check that Bukkit servers have `invsync.sync` permission for players

### JDBC Driver / "No suitable driver" Error

If you see `No suitable driver for jdbc:mariadb://...` in the Velocity logs:

**Root cause**: The MariaDB JDBC driver is shaded (relocated) inside the InvSync-Velocity JAR. In
classloader-isolated environments (Velocity plugin system), Java's `ServiceLoader` may use the
wrong classloader and fail to auto-discover the relocated driver class.

**Solution A** — Use `jdbc:mysql://` via the config override (recommended alternative):
```hocon
database {
  url = "jdbc:mysql://127.0.0.1:3306/shared_inventories"
}
```
Make sure a MySQL Connector/J driver is available (Paper ships one; for Velocity you may need to
install it manually).

**Solution B** — Explicit driver class loading (built into code):
The `DatabaseManager` now tries to explicitly load the shaded MariaDB driver
(`dev.alexanderkoch.invsync.velocity.libs.mariadb.Driver`) before creating the HikariCP pool.
This bypasses the ServiceLoader classloader issue. If the shaded driver is present in the JAR,
it will be used.

**Solution C** — Ensure the `ServicesResourceTransformer` is active in the Maven build:
Check `pom.xml` for:
```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```
This ensures `META-INF/services/java.sql.Driver` is properly merged and relocated during shading.

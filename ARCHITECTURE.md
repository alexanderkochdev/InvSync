# InvSync Architecture

## System Overview

```mermaid
graph TD
    subgraph "Velocity Proxy"
        VC[Velocity Config<br/>Groups + Sync Rules]
        VPM[Plugin Message Handler]
        VDB[(MariaDB / MySQL)]
        VHC[HikariCP Pool]
        
        VC --> VPM
        VHC --> VDB
        VPM --> VHC
    end

    subgraph "Bukkit Server 1 - Lobby"
        B1M[InvSyncBukkit]
        B1S[InventorySerializer]
        B1P[PlayerListener]
        B1R[SyncRuleManager]
        B1M --> B1S
        B1M --> B1P
        B1M --> B1R
    end

    subgraph "Bukkit Server 2 - Survival"
        B2M[InvSyncBukkit]
        B2S[InventorySerializer]
        B2P[PlayerListener]
        B2R[SyncRuleManager]
        B2M --> B2S
        B2M --> B2P
        B2M --> B2R
    end

    subgraph "Bukkit Server 3 - Minigame"
        B3M[InvSyncBukkit]
        B3S[InventorySerializer]
        B3P[PlayerListener]
        B3R[SyncRuleManager]
        B3M --> B3S
        B3M --> B3P
        B3M --> B3R
    end

    B1M -. Plugin Message .-> VPM
    B2M -. Plugin Message .-> VPM
    B3M -. Plugin Message .-> VPM
    VPM -. Plugin Message .-> B1M
    VPM -. Plugin Message .-> B2M
    VPM -. Plugin Message .-> B3M
```

## Data Flow

```mermaid
sequenceDiagram
    participant P as Player
    participant BS as Bukkit Server
    participant BV as InvSync Bukkit
    participant V as InvSync Velocity
    participant DB as MariaDB

    Note over P,DB: Player joins a server
    P->>BS: Join Event
    BS->>BV: PlayerJoinEvent
    BV->>V: Plugin Message: load_player {uuid}
    V->>V: Find server group from config
    V->>DB: SELECT player_data WHERE uuid = ?
    DB-->>V: Player data (or null)
    V-->>BV: Plugin Message: player_data {uuid, data} + sync_config {group, rules}
    BV->>BV: Deserialize inventory, apply stats
    BV->>BV: Check sync rules (skip if blocked per group)
    BV->>P: Sync complete

    Note over P,DB: Player switches to another server
    P->>BS: Quit Event
    BS->>BV: PlayerQuitEvent
    BV->>BV: Serialize inventory, ender chest, stats
    BV->>V: Plugin Message: save_player {uuid, data}
    V->>DB: INSERT ... ON DUPLICATE KEY UPDATE

    Note over P,DB: Player joins next server
    P->>BS2[Other Bukkit Server]: Join Event
    BS2->>BV2[InvSync on Other Server]: PlayerJoinEvent
    BV2->>V: Plugin Message: load_player {uuid}
    V-->>BV2: player_data + sync_config (for this server's group)
    BV2->>P: Apply data (filtered by group rules)
```

## Sync Rules Per Group

| Group | Inventory | Ender Chest | Health | Food | XP |
|-------|:---------:|:-----------:|:-----:|:----:|:--:|
| **lobby** | ✅ | ✅ | ✅ | ✅ | ❌ |
| **survival** | ✅ | ❌ | ✅ | ✅ | ✅ |
| **minigame** | ❌ | ❌ | ❌ | ❌ | ❌ |
| **creative** | ✅ | ✅ | ❌ | ❌ | ✅ |

## Message Protocol

**Channel**: `invsync:main` (namespaced)

### Bukkit → Velocity
| Type | Payload | Trigger |
|------|---------|---------|
| `load_player` | `{"uuid":"..."}` | PlayerJoinEvent |
| `save_player` | `{"uuid":"...","player_name":"...","data":{...}}` | Quit/Kick/Death |

### Velocity → Bukkit
| Type | Payload | Trigger |
|------|---------|---------|
| `player_data` | `{"uuid":"...","data":{...}}` | Response to load_player |
| `player_data_not_found` | `{"uuid":"..."}` | No data in DB |
| `sync_config` | `{"server_group":"...","sync":{...}}` | On first contact + each load |

## Security

- **No DB credentials on Bukkit servers** — all database access via Velocity
- **Prepared Statements** on Velocity for all SQL queries
- **Plugin messaging scoped** — only servers in the Velocity network can participate
- **UUID-based lookup** — prevents data leaks between players

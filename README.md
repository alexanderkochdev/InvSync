# InvSync 🔄

> **Cross-server inventory synchronization for Minecraft Paper networks — powered by Velocity.**

[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/alexanderkochdev/InvSync/releases)
[![Paper](https://img.shields.io/badge/Paper-1.21_–_1.22+-green?logo=minecraft)](https://papermc.io)
[![Velocity](https://img.shields.io/badge/Velocity-3.x-blueviolet)](https://velocitypowered.com)
[![Java](https://img.shields.io/badge/Java-21+-orange?logo=java)](https://adoptium.net)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Build](https://github.com/alexanderkochdev/InvSync/actions/workflows/build.yml/badge.svg)](https://github.com/alexanderkochdev/InvSync/actions/workflows/build.yml)

---

## ✨ Features

- **🔄 Cross-Server Inventory Sync** — Players keep their inventory across all servers
- **📦 Ender Chest Sync** — Shared ender chest contents everywhere
- **❤️ Health, Food & XP Sync** — Full player state synchronization
- **🎯 Per-Group Sync Rules** — Define exactly what syncs per server group
- **⚡ Velocity-Powered** — Central config on Velocity, lightweight Bukkit clients
- **🏊 HikariCP Connection Pooling** — High-performance MariaDB/MySQL access
- **🛡️ Secure** — No database credentials on Bukkit servers
- **🔌 1.21 & 1.22 Ready** — Tested on Paper 1.22, compatible with 1.21+

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    Velocity Proxy                                 │
│  ┌────────────────────────────────────────────────────���─────┐   │
│  │              InvSync Velocity Plugin                       │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐   │   │
│  │  │  Config   │  │ HikariCP │  │  Plugin Message     │   │   │
│  │  │  (Groups) │──┤   Pool   │──┤  Handler (JSON)     │   │   │
│  │  └──────────┘  └────┬─────┘  └──────────┬───────────┘   │   │
│  │                     │                   │                │   │
│  │                     ▼                   │                │   │
│  │              ┌──────────┐               │                │   │
│  │              │ MariaDB  │               │                │   │
│  │              │ / MySQL  │               │                │   │
│  │              └──────────┘               │                │   │
│  └──────────────────────────────────────────┼────────────────┘   │
└─────────────────────────────────────────────┼─────────────────────┘
                                              │
                        Plugin Messaging (JSON via `invsync:main`)
                                              │
                    ┌─────────────────────────┼─────────────────────┐
                    │                         │                     │
                    ▼                         ▼                     ▼
          ┌─────────────────┐     ┌─────────────────┐   ┌─────────────────┐
          │  Lobby Server   │     │ Survival Server │   │ Minigame Server │
          │  (Bukkit)       │     │  (Bukkit)       │   │  (Bukkit)       │
          │  ┌───────────┐  │     │  ┌───────────┐  │   │  ┌───────────┐  │
          │  │ Serializer│  │     │  │ Serializer│  │   │  │ Serializer│  │
          │  │ + Events  │  │     │  │ + Events  │  │   │  │ + Events  │  │
          │  └───────────┘  │     │  └───────────┘  │   │  └───────────┘  │
          │  Group: lobby   │     │  Group: survival│   │  Group: minigame│
          │  Sync: inv,ec,  │     │  Sync: inv,     │   │  Sync: none     │
          │  health,food    │     │  health,food,xp │   │                 │
          └─────────────────┘     └─────────────────┘   └─────────────────┘
```

---

## 🚀 Installation

### Prerequisites

- **Velocity** 3.x (Proxy)
- **Paper** 1.21+ (Backend Servers)
- **MariaDB** 10.6+ or **MySQL** 8.0+
- **Java** 21+

### 1. Velocity Plugin

1. Download `InvSync-Velocity-1.0.0.jar`
2. Place it in `velocity/plugins/`
3. Start Velocity once to generate the default config
4. Edit `velocity/plugins/invsync-velocity/config.yml`
5. Configure your **database credentials** and **server groups**
6. Restart Velocity

### 2. Bukkit Plugin

1. Download `InvSync-Bukkit-1.0.0.jar`
2. Place it in **every backend server's** `plugins/` folder
3. **No configuration needed** — the Bukkit plugin auto-detects its server name and receives sync rules from Velocity

---

## ⚙️ Configuration (Velocity)

All configuration is centralized on Velocity. Define server groups and per-group sync rules:

```yaml
# velocity/plugins/invsync-velocity/config.yml

database:
  host: localhost
  port: 3306
  database: invsync
  username: minecraft
  password: "your_password_here"
  table_prefix: invsync_

server_groups:
  # ── Lobby Group ──
  - name: "lobby"
    servers: ["lobby1", "lobby2"]
    sync:
      inventory: true
      ender_chest: true
      health: true
      food: true
      experience: false     # Lobby hat eigene XP

  # ── Survival Group ──
  - name: "survival"
    servers: ["survival1", "survival2"]
    sync:
      inventory: true
      ender_chest: false    # Survival hat eigenen Enderchest
      health: true
      food: true
      experience: true

  # ── Minigame Group ──
  - name: "minigame"
    servers: ["minigame1", "minigame2", "minigame3"]
    sync:
      inventory: false      # Minigames nutzen Kits
      ender_chest: false
      health: false
      food: false
      experience: false
```

---

## 🌐 Built for malimala.net

InvSync is developed and used in production on **[malimala.net](https://malimala.net)** — a German Minecraft network with multiple servers.

> 🎮 **Join us at malimala.net!**  
> We offer Survival, Creative, Minigames, and more.  
> Inventory is synced across all servers — thanks to InvSync! 😉

---

## 🧑‍💻 Building from Source

```bash
# Clone
git clone https://github.com/alexanderkochdev/InvSync.git
cd InvSync

# Build all modules
mvn clean package

# Output:
#   invsync-api/target/invsync-api-1.0.0.jar
#   invsync-bukkit/target/InvSync-Bukkit-1.0.0.jar    ← for Paper servers
#   invsync-velocity/target/InvSync-Velocity-1.0.0.jar ← for Velocity
```

### 🤖 GitHub Actions

Every push to `main`, `master`, or a `v*` tag automatically builds all modules.  
Build artifacts are available in the workflow run.

![Build Status](https://github.com/alexanderkochdev/InvSync/actions/workflows/build.yml/badge.svg)

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

## 👨‍💻 Author

**Alexander Koch**
- 🌐 [alexanderkoch.dev](https://alexanderkoch.dev)
- 🎮 [malimala.net](https://malimala.net) — Minecraft Server
- 🐙 [GitHub @alexanderkochdev](https://github.com/alexanderkochdev)

> 💡 **Want to support the project?** Join [malimala.net](https://malimala.net) and give us a ⭐ on GitHub!

# Changelog

## [1.0.6] - 2026-07-17

### Fixed
- **Source-Type-Resolution**: Velocity 3.5.0 liefert `VelocityServerConnection` (konkrete Klasse)
  als `event.getSource()`, nicht `ServerConnection` (Interface). Der korrekte Import
  `com.velocitypowered.api.proxy.ServerConnection` (ohne `.server`) + `instanceof ServerConnection`
  Check in `resolveSourceServer()` fängt diesen Fall jetzt ab.

## [1.0.5] - 2026-07-17

### Fixed
- **PluginMessage Channel-Parsing**: Velocity 3.5.0 hängt `(modern)` an den Channel-Namen
  (`"invsync:main (modern)"` statt `"invsync:main"`) bei modern-forwarding Channels.
  Der Channel-Name wird jetzt korrekt per `split("\\s+")[0]` vor dem Leerzeichen extrahiert.
  Dies war die eigentliche Ursache — alle vorherigen Fixes (String-Compare, Player-Source,
  Konstruktor-Registrierung) konnten nicht greifen, weil der Channel-Compare nie matchte.

## [1.0.4] - 2026-07-17

### Changed
- **Channel-Registrierung im Konstruktor**: `proxy.getChannelRegistrar().register(CHANNEL)` wird
  jetzt bereits im Plugin-Konstruktor aufgerufen (statt in `onProxyInitialization()`), damit
  Velocity den Channel kennt, bevor Backend-Server verbinden.
- **Radikales Diagnose-Logging**: `onProxyInitialization()` loggt Schritt-für-Schritt
  (Steps 1–7) auf `logger.info`. `PluginMessageHandler.onPluginMessage()` loggt JEDES
  `PluginMessageEvent` (Channel, Source-Typ, Klasse, Datenlänge) zur Fehleranalyse.

## [1.0.3] - 2026-07-17

### Fixed
- **PluginMessage Channel-Vergleich**: Channel-Identifier wird jetzt als String verglichen
  (`"invsync:main".equals(channelName)`) statt als Object-Equals. In Velocity 3.5.0 kann der
  `event.getIdentifier()` ein anderer Typ sein (z.B. ResourceLocation) als der registrierte
  `MinecraftChannelIdentifier`, was den `equals()`-Vergleich fehlschlagen lässt.
- **Diagnose-Logging**: Empfangene PluginMessages werden mit Channel, Source-Typ und
  Datenlänge geloggt — sofort sichtbar bei der nächsten Spieler-Verbindung.

### Changed
- `PluginMessageHandler.onPluginMessage()`: String-basierter Channel-Vergleich + extrahierte
  `resolveSourceServer()`-Methode für cleaner Source-Resolution-Code

## [1.0.2] - 2026-07-17

### Fixed
- **Bukkit ↔ Velocity Plugin-Kommunikation**: `onPluginMessage()` prüft jetzt auch `Player`
  als `event.getSource()`, da Bukkit's `player.sendPluginMessage()` die Nachricht durch den
  Player-Tunnel an Velocity sendet (nicht direkt vom Backend-Server). Der Source ist in
  Velocity 3.x der `Player`, nicht der `RegisteredServer` — der vorherige Check
  `event.getSource() instanceof RegisteredServer` hat alle Bukkit-Nachrichten blockiert.
- **Logging**: Empfangene PluginMessages werden auf `logger.info` statt `logger.debug` geloggt
  ("Received plugin message type '...' from server ...").

## [1.0.1] - 2026-07-17

### Fixed
- **JDBC Driver Loading (Velocity)**: Explizites Driver-Loading in `DatabaseManager.initialize()`
  umgeht das Classloader-Isolationsproblem in Velocity's Plugin-System. Der shaded MariaDB
  JDBC-Treiber wird nun via `Class.forName()` geladen und explizit per
  `hikariConfig.setDriverClassName()` gesetzt — funktioniert auch wenn der `ServiceLoader`
  in isolierten Classloadern versagt.
- **Config**: Neue `database.url`-Option in `config.yml` erlaubt direkte Angabe einer
  JDBC-URL (z.B. `jdbc:mysql://127.0.0.1:3306/shared_inventories`), falls der MariaDB-Treiber
  im Velocity-Plugin-System nicht ladbar ist.
- **Logging**: `InvSyncVelocity` loggt korrekt "DEGRADED MODE" statt irreführend
  "initialized successfully", wenn die Datenbank nicht erreichbar ist.

### Changed
- `VelocityConfig.java`: `getJdbcUrl()` prüft zuerst `database.url`-Override-Feld; neuer `getDbUrl()`-Getter
- `DatabaseManager.java`: Neue `loadJdbcDriver()`-Methode mit 4 Driver-Kandidaten (shaded MariaDB,
  unshaded MariaDB, MySQL Connector/J neu + alt)
- `InvSyncVelocity.java`: Health-Check nach DB-Initialisierung für korrektes Logging
- `AGENTS.md`: Config-Doku aktualisiert + JDBC-Debugging-Sektion hinzugefügt

## [1.0.0] - 2026-07-14

### Added
- Initial release of InvSync
- Inventory synchronization across Velocity proxy network
- NBT-based ItemStack serialization (Paper `serializeAsBytes()`)
- Per-server-group sync rules (inventory, ender chest, health, food, experience)
- MariaDB storage with HikariCP connection pooling
- Redis cache (optional, read-through/write-through)
- Data versioning for race-condition protection
- Chunked plugin messaging for large payloads (>48KB)
- Gson-based JSON communication (no manual StringBuilder)
- Sync-rule filtering on both load AND save (no cross-group contamination)
- Admin commands: `/invsync reload`, `/invsync status`, `/invsync sync <player>`, `/invsync cache clear|stats`
- Automatic chunk buffer cleanup (30s interval)

### Fixed
- Velocity plugin crash on startup due to Typesafe-Config library conflict
  - Shaded `ninja.leaping.configurate` (Configurate 3.x) into plugin JAR with relocated package
  - Shaded `com.typesafe.config` into plugin JAR with relocated package
  - Isolated plugin's Configurate/Typesafe classes from Velocity's built-in Configurate 4.x
  - Added `com.google.inject:guice` as compile dependency (was only transitive via velocity-api)
- MariaDB JDBC driver not found at runtime (`No suitable driver`)
  - Added `ServicesResourceTransformer` to shade plugin for correct ServiceLoader registration
  - Transformed `META-INF/services/java.sql.Driver` to use relocated class name

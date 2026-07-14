# Changelog

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

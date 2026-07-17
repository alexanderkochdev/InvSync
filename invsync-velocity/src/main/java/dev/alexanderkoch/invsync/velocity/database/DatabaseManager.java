package dev.alexanderkoch.invsync.velocity.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.alexanderkoch.invsync.velocity.config.VelocityConfig;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the HikariCP connection pool for MariaDB.
 * Handles schema creation and provides pooled connections.
 */
public class DatabaseManager implements AutoCloseable {

    private final VelocityConfig config;
    private final Logger logger;
    private HikariDataSource dataSource;

    public DatabaseManager(VelocityConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /** Initialize the connection pool and ensure the schema exists. */
    public void initialize() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getDbUsername());
        hikariConfig.setPassword(config.getDbPassword());

        // Explicit driver loading — required in classloader-isolated environments
        // (Velocity plugin system) where the ServiceLoader may fail to auto-discover
        // the shaded MariaDB JDBC driver. See AGENTS.md for details.
        loadJdbcDriver(hikariConfig);

        hikariConfig.setMaximumPoolSize(config.getDbPoolSize());
        hikariConfig.setMaxLifetime(config.getDbMaxLifetime());
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setIdleTimeout(600_000);
        hikariConfig.setPoolName("InvSync-Hikari");

        // MariaDB optimizations
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");

        try {
            dataSource = new HikariDataSource(hikariConfig);
            createSchema();
            logger.info("Database connection pool initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize database connection pool. "
                    + "Check that a compatible JDBC driver is available for: {}",
                    config.getJdbcUrl(), e);
        }
    }

    /**
     * Explicitly loads a JDBC driver class and registers it with HikariCP.
     * Tries multiple driver class names as fallbacks (shaded MariaDB,
     * unshaded MariaDB, MySQL Connector/J).
     *
     * In a shaded JAR, the driver class is relocated from its original package
     * (e.g. {@code org.mariadb.jdbc.Driver}) to the shaded package
     * (e.g. {@code dev.alexanderkoch.invsync.velocity.libs.mariadb.Driver}).
     * Java's {@link java.sql.DriverManager} discovers drivers via
     * {@link java.util.ServiceLoader} reading {@code META-INF/services/java.sql.Driver},
     * but in classloader-isolated environments (plugin containers like Velocity),
     * the ServiceLoader may use the wrong classloader. This method works around that
     * by loading the driver class explicitly via the plugin's own classloader.
     */
    private void loadJdbcDriver(HikariConfig hikariConfig) {
        String[] candidates = {
            "dev.alexanderkoch.invsync.velocity.libs.mariadb.Driver",
            "org.mariadb.jdbc.Driver",
            "com.mysql.cj.jdbc.Driver",
            "com.mysql.jdbc.Driver"
        };

        for (String driverClass : candidates) {
            try {
                Class.forName(driverClass);
                hikariConfig.setDriverClassName(driverClass);
                logger.debug("Explicitly loaded JDBC driver: {}", driverClass);
                return;
            } catch (ClassNotFoundException e) {
                // Try next candidate
            }
        }

        logger.warn("No JDBC driver found via explicit loading. "
                + "Falling back to ServiceLoader/DriverManager auto-discovery for URL: {}",
                config.getJdbcUrl());
    }

    /** Create tables if they don't exist. Updated schema with data_version column. */
    private void createSchema() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS invsync_player_inventories ("
                + "uuid VARCHAR(36) PRIMARY KEY,"
                + "player_name VARCHAR(16) NOT NULL,"
                + "inventory LONGTEXT,"
                + "ender_chest LONGTEXT,"
                + "health DOUBLE DEFAULT 20.0,"
                + "max_health DOUBLE DEFAULT 20.0,"
                + "food INT DEFAULT 20,"
                + "saturation FLOAT DEFAULT 5.0,"
                + "level INT DEFAULT 0,"
                + "exp FLOAT DEFAULT 0.0,"
                + "total_experience INT DEFAULT 0,"
                + "data_version INT DEFAULT 0,"
                + "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);

            // Add data_version column if it doesn't exist (migration from v1.0.0)
            try {
                stmt.execute("ALTER TABLE invsync_player_inventories "
                        + "ADD COLUMN data_version INT DEFAULT 0 "
                        + "AFTER total_experience");
            } catch (SQLException ignored) {
                // Column already exists — safe to ignore
            }

            logger.info("Database schema verified");
        }
    }

    /** Get a connection from the pool. Always use try-with-resources. */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database pool is not initialized");
        }
        return dataSource.getConnection();
    }

    /** Check if the pool is healthy. */
    public boolean isHealthy() {
        if (dataSource == null || dataSource.isClosed()) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Get pool status metrics. */
    public HikariDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection pool closed");
        }
    }
}

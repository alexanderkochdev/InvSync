package dev.alexanderkoch.invsync.velocity.database;

import com.google.gson.JsonObject;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import org.slf4j.Logger;

import java.sql.*;
import java.util.*;

/**
 * CRUD operations for player inventory data in MariaDB.
 * Supports data versioning for race-condition protection and selective field saving
 * based on sync rules.
 *
 * <h3>Race-Condition Protection</h3>
 * Each save increments a {@code data_version} counter. Loads return the current version.
 * The save operation uses {@code UPDATE ... WHERE data_version = ?} to detect conflicts
 * when a newer version has been written concurrently.
 */
public class InventoryRepository {

    private final DatabaseManager db;
    private final Logger logger;
    private final com.google.gson.Gson gson = InvSyncChannel.gson();

    /** Player data record returned from load operations. */
    public static class PlayerData {
        private final String uuid;
        private final String playerName;
        private final JsonObject data;
        private final int dataVersion;

        public PlayerData(String uuid, String playerName, JsonObject data, int dataVersion) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.data = data;
            this.dataVersion = dataVersion;
        }

        public String getUuid() { return uuid; }
        public String getPlayerName() { return playerName; }
        public JsonObject getData() { return data; }
        public int getDataVersion() { return dataVersion; }

        /** True if this record has no inventory data at all (first join). */
        public boolean isEmpty() {
            return data == null || data.size() == 0;
        }
    }

    public InventoryRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    // ── Load ───────────────────────────────────────────────────────

    /**
     * Load a player's saved data from the database.
     *
     * @param uuid the player's UUID
     * @return PlayerData with all fields, or empty PlayerData if not found
     */
    public PlayerData loadPlayer(String uuid) {
        String sql = "SELECT player_name, inventory, ender_chest, health, max_health, "
                + "food, saturation, level, exp, total_experience, data_version "
                + "FROM invsync_player_inventories WHERE uuid = ?";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonObject data = new JsonObject();

                    String inventory = rs.getString("inventory");
                    if (inventory != null) data.addProperty(InvSyncChannel.KEY_INVENTORY, inventory);

                    String enderChest = rs.getString("ender_chest");
                    if (enderChest != null) data.addProperty(InvSyncChannel.KEY_ENDER_CHEST, enderChest);

                    data.addProperty(InvSyncChannel.KEY_HEALTH, String.valueOf(rs.getDouble("health")));
                    data.addProperty(InvSyncChannel.KEY_MAX_HEALTH, String.valueOf(rs.getDouble("max_health")));
                    data.addProperty(InvSyncChannel.KEY_FOOD, String.valueOf(rs.getInt("food")));
                    data.addProperty(InvSyncChannel.KEY_SATURATION, String.valueOf(rs.getFloat("saturation")));
                    data.addProperty(InvSyncChannel.KEY_LEVEL, String.valueOf(rs.getInt("level")));
                    data.addProperty(InvSyncChannel.KEY_EXP, String.valueOf(rs.getFloat("exp")));
                    data.addProperty(InvSyncChannel.KEY_TOTAL_EXPERIENCE, String.valueOf(rs.getInt("total_experience")));

                    return new PlayerData(uuid, rs.getString("player_name"), data, rs.getInt("data_version"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load player data for {}: {}", uuid, e.getMessage());
        }

        return new PlayerData(uuid, "", new JsonObject(), 0);
    }

    // ── Save with Sync-Rule Filtering ──────────────────────────────

    /**
     * Save a player's data. Only fields that are enabled in syncRules will be
     * written to the database. Uses data_version to prevent race conditions.
     * <p>
     * If the player doesn't exist yet, INSERTs a new row.
     * If the player exists, UPDATEs with optimistic locking via data_version.
     *
     * @param uuid        player UUID
     * @param playerName  player name
     * @param data        the full data from Bukkit
     * @param syncRules   which fields to actually persist (from server group config)
     * @return true if the save was successful
     */
    public boolean savePlayer(String uuid, String playerName, JsonObject data, Map<String, Boolean> syncRules) {
        // First, check if the player already has data
        String checkSql = "SELECT data_version FROM invsync_player_inventories WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement checkPs = conn.prepareStatement(checkSql)) {

            checkPs.setString(1, uuid);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (rs.next()) {
                    // Existing player — UPDATE with version check
                    int currentVersion = rs.getInt("data_version");
                    return updatePlayer(conn, uuid, playerName, data, syncRules, currentVersion);
                } else {
                    // New player — INSERT
                    return insertPlayer(conn, uuid, playerName, data, syncRules);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to save player data for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    /** INSERT a new player row. */
    private boolean insertPlayer(Connection conn, String uuid, String playerName,
                                  JsonObject data, Map<String, Boolean> syncRules) throws SQLException {
        String sql = "INSERT INTO invsync_player_inventories ("
                + "uuid, player_name, inventory, ender_chest, "
                + "health, max_health, food, saturation, "
                + "level, exp, total_experience, data_version"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, playerName);
            applySyncFilteredFields(ps, data, syncRules, 3);
            ps.executeUpdate();
            return true;
        }
    }

    /** UPDATE existing player with optimistic locking via data_version. */
    private boolean updatePlayer(Connection conn, String uuid, String playerName,
                                  JsonObject data, Map<String, Boolean> syncRules,
                                  int currentVersion) throws SQLException {
        // Build SET clause dynamically — only include synced fields
        StringBuilder setClause = new StringBuilder();
        List<Object> params = new ArrayList<>();
        int newVersion = currentVersion + 1;

        appendSetField(setClause, params, "inventory = ?",
                data.has(InvSyncChannel.KEY_INVENTORY) && syncRules.getOrDefault(InvSyncChannel.SYNC_INVENTORY, true),
                getSyncString(data, InvSyncChannel.KEY_INVENTORY));

        appendSetField(setClause, params, "ender_chest = ?",
                data.has(InvSyncChannel.KEY_ENDER_CHEST) && syncRules.getOrDefault(InvSyncChannel.SYNC_ENDER_CHEST, true),
                getSyncString(data, InvSyncChannel.KEY_ENDER_CHEST));

        appendSetField(setClause, params, "health = ?",
                syncRules.getOrDefault(InvSyncChannel.SYNC_HEALTH, true),
                getSyncDouble(data, InvSyncChannel.KEY_HEALTH, 20.0));

        appendSetField(setClause, params, "max_health = ?",
                syncRules.getOrDefault(InvSyncChannel.SYNC_HEALTH, true),
                getSyncDouble(data, InvSyncChannel.KEY_MAX_HEALTH, 20.0));

        appendSetField(setClause, params, "food = ?",
                syncRules.getOrDefault(InvSyncChannel.SYNC_FOOD, true),
                getSyncInt(data, InvSyncChannel.KEY_FOOD, 20));

        appendSetField(setClause, params, "saturation = ?",
                syncRules.getOrDefault(InvSyncChannel.SYNC_FOOD, true),
                getSyncFloat(data, InvSyncChannel.KEY_SATURATION, 5.0f));

        appendSetField(setClause, params, "level = ?",
                syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true),
                getSyncInt(data, InvSyncChannel.KEY_LEVEL, 0));

        appendSetField(setClause, params, "exp = ?",
                syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true),
                getSyncFloat(data, InvSyncChannel.KEY_EXP, 0.0f));

        appendSetField(setClause, params, "total_experience = ?",
                syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true),
                getSyncInt(data, InvSyncChannel.KEY_TOTAL_EXPERIENCE, 0));

        // Always update player_name, data_version, last_updated
        setClause.append(", player_name = ?, data_version = ?");

        // Optimistic locking: only update if version matches
        String sql = "UPDATE invsync_player_inventories SET " + setClause
                + " WHERE uuid = ? AND data_version = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Object param : params) {
                ps.setObject(idx++, param);
            }
            ps.setString(idx++, playerName);
            ps.setInt(idx++, newVersion);
            ps.setString(idx++, uuid);
            ps.setInt(idx, currentVersion);

            int affected = ps.executeUpdate();
            if (affected == 0) {
                // Race condition detected! A newer version was saved by another server.
                logger.warn("Race condition saving player {} (uuid={}): expected version {} but another save occurred. "
                                + "Data from this save was discarded to prevent overwrite.",
                        playerName, uuid, currentVersion);
                return false;
            }
            return true;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** Apply all sync-filtered fields starting at the given parameter index. */
    private void applySyncFilteredFields(PreparedStatement ps, JsonObject data,
                                          Map<String, Boolean> syncRules, int startIdx) throws SQLException {
        int idx = startIdx;

        // Inventory & Ender Chest (only if enabled)
        ps.setString(idx++, syncRules.getOrDefault(InvSyncChannel.SYNC_INVENTORY, true)
                ? getSyncString(data, InvSyncChannel.KEY_INVENTORY) : null);
        ps.setString(idx++, syncRules.getOrDefault(InvSyncChannel.SYNC_ENDER_CHEST, true)
                ? getSyncString(data, InvSyncChannel.KEY_ENDER_CHEST) : null);

        // Health (sync rule controls both health and max_health together)
        boolean syncHealth = syncRules.getOrDefault(InvSyncChannel.SYNC_HEALTH, true);
        ps.setDouble(idx++, syncHealth ? getSyncDouble(data, InvSyncChannel.KEY_HEALTH, 20.0) : 20.0);
        ps.setDouble(idx++, syncHealth ? getSyncDouble(data, InvSyncChannel.KEY_MAX_HEALTH, 20.0) : 20.0);

        // Food
        boolean syncFood = syncRules.getOrDefault(InvSyncChannel.SYNC_FOOD, true);
        ps.setInt(idx++, syncFood ? getSyncInt(data, InvSyncChannel.KEY_FOOD, 20) : 20);
        ps.setFloat(idx++, syncFood ? getSyncFloat(data, InvSyncChannel.KEY_SATURATION, 5.0f) : 5.0f);

        // Experience
        boolean syncExp = syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true);
        ps.setInt(idx++, syncExp ? getSyncInt(data, InvSyncChannel.KEY_LEVEL, 0) : 0);
        ps.setFloat(idx++, syncExp ? getSyncFloat(data, InvSyncChannel.KEY_EXP, 0.0f) : 0.0f);
        ps.setInt(idx, syncExp ? getSyncInt(data, InvSyncChannel.KEY_TOTAL_EXPERIENCE, 0) : 0);
    }

    /** Add a field to the SET clause and params list only if the condition is true. */
    private void appendSetField(StringBuilder setClause, List<Object> params,
                                 String fieldExpr, boolean condition, Object value) {
        if (condition) {
            if (setClause.length() > 0) setClause.append(", ");
            setClause.append(fieldExpr);
            params.add(value);
        }
    }

    // ── Type-Safe Data Extractors ──────────────────────────────────

    private String getSyncString(JsonObject data, String key) {
        return data.has(key) && !data.get(key).isJsonNull() ? data.get(key).getAsString() : null;
    }

    private double getSyncDouble(JsonObject data, String key, double def) {
        try {
            return data.has(key) ? Double.parseDouble(data.get(key).getAsString()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private float getSyncFloat(JsonObject data, String key, float def) {
        try {
            return data.has(key) ? Float.parseFloat(data.get(key).getAsString()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private int getSyncInt(JsonObject data, String key, int def) {
        try {
            return data.has(key) ? Integer.parseInt(data.get(key).getAsString()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ── Delete ─────────────────────────────────────────────────────

    /** Delete a player's data entirely. Used for testing/admin commands. */
    public boolean deletePlayer(String uuid) {
        String sql = "DELETE FROM invsync_player_inventories WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to delete player data for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    /** Get total number of stored players (for status command). */
    public int getPlayerCount() {
        String sql = "SELECT COUNT(*) FROM invsync_player_inventories";
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logger.error("Failed to get player count", e);
            return -1;
        }
    }
}

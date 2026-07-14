package dev.alexanderkoch.invsync.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * InvSync communication protocol constants and JSON helpers.
 *
 * <h3>Plugin Messaging Channel</h3>
 * All communication happens on the channel {@value #CHANNEL} via Velocity
 * plugin messaging. Messages are UTF-8 encoded JSON with a maximum payload
 * of ~50KB per chunk. Larger payloads are split into {@code data_chunk}
 * messages with a {@code chunk_index} and {@code total_chunks} field.
 *
 * <h3>Message Flow</h3>
 * <pre>
 * Bukkit → Velocity:
 *   load_player    (player joined a server)
 *   save_player    (player quit, kicked, or died)
 *
 * Velocity → Bukkit:
 *   player_data          (saved data exists → apply to player)
 *   player_data_not_found (no saved data → first join)
 *   sync_config          (group sync rules for this server)
 *   data_chunk           (partial payload fragment)
 * </pre>
 */
public final class InvSyncChannel {

    /** The plugin messaging channel name. */
    public static final String CHANNEL = "invsync:main";

    // ── Message Types ──────────────────────────────────────────────

    /** Bukkit → Velocity: Player joined a server, request saved data. */
    public static final String TYPE_LOAD_PLAYER = "load_player";
    /** Bukkit → Velocity: Player quit/kicked/died, save data. */
    public static final String TYPE_SAVE_PLAYER = "save_player";

    /** Velocity → Bukkit: Saved player data found. */
    public static final String TYPE_PLAYER_DATA = "player_data";
    /** Velocity → Bukkit: No saved data for this player (first join). */
    public static final String TYPE_PLAYER_DATA_NOT_FOUND = "player_data_not_found";
    /** Velocity → Bukkit: Sync configuration for this server group. */
    public static final String TYPE_SYNC_CONFIG = "sync_config";

    /** Chunked message fragment (used for payloads > 50KB). */
    public static final String TYPE_DATA_CHUNK = "data_chunk";

    // ── JSON Field Keys ────────────────────────────────────────────

    public static final String KEY_TYPE = "type";
    public static final String KEY_UUID = "uuid";
    public static final String KEY_PLAYER_NAME = "player_name";
    public static final String KEY_DATA = "data";
    public static final String KEY_DATA_VERSION = "data_version";
    public static final String KEY_SERVER_GROUP = "server_group";
    public static final String KEY_SYNC = "sync";

    // Inventory data keys
    public static final String KEY_INVENTORY = "inventory";
    public static final String KEY_ENDER_CHEST = "ender_chest";
    public static final String KEY_HEALTH = "health";
    public static final String KEY_MAX_HEALTH = "max_health";
    public static final String KEY_FOOD = "food";
    public static final String KEY_SATURATION = "saturation";
    public static final String KEY_LEVEL = "level";
    public static final String KEY_EXP = "exp";
    public static final String KEY_TOTAL_EXPERIENCE = "total_experience";

    // Chunking keys
    public static final String KEY_CHUNK_INDEX = "chunk_index";
    public static final String KEY_TOTAL_CHUNKS = "total_chunks";
    public static final String KEY_CHUNK_DATA = "chunk_data";
    public static final String KEY_CHUNK_SESSION = "chunk_session";

    // ── Sync rule keys (map inside sync_config) ────────────────────

    public static final String SYNC_INVENTORY = "inventory";
    public static final String SYNC_ENDER_CHEST = "ender_chest";
    public static final String SYNC_HEALTH = "health";
    public static final String SYNC_FOOD = "food";
    public static final String SYNC_EXPERIENCE = "experience";

    /** Default sync rules (all true). */
    public static final java.util.Map<String, Boolean> DEFAULT_SYNC_RULES = java.util.Map.of(
            SYNC_INVENTORY, true,
            SYNC_ENDER_CHEST, true,
            SYNC_HEALTH, true,
            SYNC_FOOD, true,
            SYNC_EXPERIENCE, true
    );

    // ── Gson Instance (thread-safe, shared) ────────────────────────

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()   // preserve Base64 =/+ chars
            .create();

    private static final JsonParser PARSER = new JsonParser();

    // ── Helper Methods ─────────────────────────────────────────────

    /** Shared Gson instance (disableHtmlEscaping enabled). */
    public static Gson gson() {
        return GSON;
    }

    /** Parse a JSON string into a JsonObject. */
    public static JsonObject parse(String json) {
        return PARSER.parse(json).getAsJsonObject();
    }

    /** Build a simple message with just a type. */
    public static JsonObject message(String type) {
        JsonObject msg = new JsonObject();
        msg.addProperty(KEY_TYPE, type);
        return msg;
    }

    /** Build a type + uuid + player_name message. */
    public static JsonObject playerMessage(String type, String uuid, String playerName) {
        JsonObject msg = message(type);
        msg.addProperty(KEY_UUID, uuid);
        msg.addProperty(KEY_PLAYER_NAME, playerName);
        return msg;
    }

    /** Convert a JsonObject to a UTF-8 byte array. */
    public static byte[] toBytes(JsonObject json) {
        return GSON.toJson(json).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Convert a byte array back to a JsonObject. */
    public static JsonObject fromBytes(byte[] data) {
        return PARSER.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }

    // ── Chunking Helpers ───────────────────────────────────────────

    /** Maximum size of a single plugin message payload (48KB to stay safe). */
    public static final int MAX_CHUNK_SIZE = 48 * 1024;

    /**
     * Splits a large JsonObject into chunk messages if it exceeds {@link #MAX_CHUNK_SIZE}.
     * Returns a list of chunk JsonObjects, each with type=data_chunk, chunk_session, chunk_index,
     * total_chunks, and chunk_data.
     *
     * @param fullMessage the complete message to potentially split
     * @param sessionId   a unique session identifier (e.g. UUID for this transfer)
     * @return list of 1 or more chunk messages
     */
    public static java.util.List<JsonObject> chunkIfNeeded(JsonObject fullMessage, String sessionId) {
        byte[] raw = toBytes(fullMessage);
        if (raw.length <= MAX_CHUNK_SIZE) {
            return java.util.Collections.singletonList(fullMessage);
        }

        int chunkSize = MAX_CHUNK_SIZE;
        int totalChunks = (int) Math.ceil((double) raw.length / chunkSize);
        java.util.List<JsonObject> chunks = new java.util.ArrayList<>(totalChunks);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, raw.length);
            byte[] chunkBytes = new byte[end - start];
            System.arraycopy(raw, start, chunkBytes, 0, chunkBytes.length);

            JsonObject chunk = message(TYPE_DATA_CHUNK);
            chunk.addProperty(KEY_CHUNK_SESSION, sessionId);
            chunk.addProperty(KEY_CHUNK_INDEX, i);
            chunk.addProperty(KEY_TOTAL_CHUNKS, totalChunks);
            chunk.addProperty(KEY_CHUNK_DATA, java.util.Base64.getEncoder().encodeToString(chunkBytes));
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * Reassembles a list of chunk messages (sorted by chunk_index) into the original JSON.
     *
     * @param chunks list of chunk JsonObjects
     * @return the reassembled full message
     */
    public static JsonObject reassembleChunks(java.util.List<JsonObject> chunks) {
        chunks.sort((a, b) ->
                Integer.compare(a.get(KEY_CHUNK_INDEX).getAsInt(), b.get(KEY_CHUNK_INDEX).getAsInt()));

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        for (JsonObject chunk : chunks) {
            byte[] chunkBytes = java.util.Base64.getDecoder().decode(chunk.get(KEY_CHUNK_DATA).getAsString());
            baos.write(chunkBytes, 0, chunkBytes.length);
        }
        return fromBytes(baos.toByteArray());
    }

    private InvSyncChannel() {
        throw new UnsupportedOperationException("Utility class");
    }
}

package dev.alexanderkoch.invsync.bukkit.sync;

import com.google.gson.JsonObject;
import dev.alexanderkoch.invsync.api.InvSyncChannel;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Serializes and deserializes player data using Paper's NBT-based ItemStack serialization.
 * <p>
 * Uses {@link ItemStack#serializeAsBytes()} and {@link ItemStack#deserializeBytes(byte[])}
 * which is the standard Paper API since 1.20.5+.
 * <p>
 * <b>Format:</b>
 * {@code [0x4E (magic)][int32 itemCount][int32 len1][byte[] data1]...[int32 lenN][byte[] dataN]}
 * <ul>
 *   <li>Empty/null slots are stored as {@code [0x00 0x00 0x00 0x01][0x00]} (len=1, byte=0)</li>
 *   <li>The magic byte {@code 0x4E} ('N') allows future format detection</li>
 * </ul>
 */
public final class InventorySerializer {

    /** Magic byte identifying the NBT-based format (0x4E = 'N'). */
    private static final byte MAGIC_NBT = 0x4E;

    private static final Logger LOGGER = Logger.getLogger(InventorySerializer.class.getName());

    // ── Serialization (Player → JSON) ──────────────────────────────

    /**
     * Serialize a player's full state (inventory, ender chest, health, XP, food) into a JsonObject.
     *
     * @param player the player to serialize
     * @return JsonObject with all player data encoded as Base64 strings or number strings
     */
    public static JsonObject serializePlayer(Player player) {
        JsonObject data = new JsonObject();

        data.addProperty(InvSyncChannel.KEY_INVENTORY, serializeItems(player.getInventory().getContents()));
        data.addProperty(InvSyncChannel.KEY_ENDER_CHEST, serializeItems(player.getEnderChest().getContents()));

        data.addProperty(InvSyncChannel.KEY_HEALTH, String.valueOf(player.getHealth()));
        data.addProperty(InvSyncChannel.KEY_MAX_HEALTH, String.valueOf(player.getMaxHealth()));
        data.addProperty(InvSyncChannel.KEY_FOOD, String.valueOf(player.getFoodLevel()));
        data.addProperty(InvSyncChannel.KEY_SATURATION, String.valueOf(player.getSaturation()));

        data.addProperty(InvSyncChannel.KEY_LEVEL, String.valueOf(player.getLevel()));
        data.addProperty(InvSyncChannel.KEY_EXP, String.valueOf(player.getExp()));
        data.addProperty(InvSyncChannel.KEY_TOTAL_EXPERIENCE, String.valueOf(player.getTotalExperience()));

        return data;
    }

    // ── Deserialization (JSON → Player) ────────────────────────────

    /**
     * Apply player data from a JsonObject to a player entity.
     * Respects the sync rules to only apply permitted fields.
     */
    public static void deserializePlayer(Player player, JsonObject data, Map<String, Boolean> syncRules) {
        if (syncRules.getOrDefault(InvSyncChannel.SYNC_INVENTORY, true)
                && data.has(InvSyncChannel.KEY_INVENTORY)) {
            ItemStack[] items = deserializeItems(data.get(InvSyncChannel.KEY_INVENTORY).getAsString());
            if (items != null) {
                player.getInventory().setContents(items);
            }
        }

        if (syncRules.getOrDefault(InvSyncChannel.SYNC_ENDER_CHEST, true)
                && data.has(InvSyncChannel.KEY_ENDER_CHEST)) {
            ItemStack[] items = deserializeItems(data.get(InvSyncChannel.KEY_ENDER_CHEST).getAsString());
            if (items != null) {
                player.getEnderChest().setContents(items);
            }
        }

        if (syncRules.getOrDefault(InvSyncChannel.SYNC_HEALTH, true)) {
            try {
                if (data.has(InvSyncChannel.KEY_MAX_HEALTH)) {
                    player.setMaxHealth(Double.parseDouble(data.get(InvSyncChannel.KEY_MAX_HEALTH).getAsString()));
                }
                if (data.has(InvSyncChannel.KEY_HEALTH)) {
                    player.setHealth(Math.min(
                            Double.parseDouble(data.get(InvSyncChannel.KEY_HEALTH).getAsString()),
                            player.getMaxHealth()));
                }
            } catch (NumberFormatException ignored) {}
        }

        if (syncRules.getOrDefault(InvSyncChannel.SYNC_FOOD, true)) {
            try {
                if (data.has(InvSyncChannel.KEY_FOOD))
                    player.setFoodLevel(Integer.parseInt(data.get(InvSyncChannel.KEY_FOOD).getAsString()));
                if (data.has(InvSyncChannel.KEY_SATURATION))
                    player.setSaturation(Float.parseFloat(data.get(InvSyncChannel.KEY_SATURATION).getAsString()));
            } catch (NumberFormatException ignored) {}
        }

        if (syncRules.getOrDefault(InvSyncChannel.SYNC_EXPERIENCE, true)) {
            try {
                if (data.has(InvSyncChannel.KEY_LEVEL))
                    player.setLevel(Integer.parseInt(data.get(InvSyncChannel.KEY_LEVEL).getAsString()));
                if (data.has(InvSyncChannel.KEY_EXP))
                    player.setExp(Float.parseFloat(data.get(InvSyncChannel.KEY_EXP).getAsString()));
                if (data.has(InvSyncChannel.KEY_TOTAL_EXPERIENCE))
                    player.setTotalExperience(Integer.parseInt(data.get(InvSyncChannel.KEY_TOTAL_EXPERIENCE).getAsString()));
            } catch (NumberFormatException ignored) {}
        }
    }

    // ── ItemStack[] ↔ Base64 (NBT-based) ──────────────────────────

    /**
     * Serialize an array of ItemStacks into a Base64-encoded string.
     *
     * <b>Format:</b><pre>{@code
     * [0x4E (magic)][int32 itemCount][int32 len1][byte[] data1]...[int32 lenN][byte[] dataN]
     * }</pre>
     * <ul>
     *   <li>Empty/null slots: {@code data = [0x00]} (len=1)</li>
     *   <li>Items: {@code data = ItemStack.serializeAsBytes()}</li>
     * </ul>
     *
     * @param items array of ItemStacks to serialize
     * @return Base64-encoded string, or empty string if null/empty
     */
    public static String serializeItems(ItemStack[] items) {
        if (items == null || items.length == 0) return "";

        try {
            byte[][] allBytes = new byte[items.length][];
            int totalLength = 0;

            for (int i = 0; i < items.length; i++) {
                if (items[i] == null || items[i].isEmpty()) {
                    allBytes[i] = new byte[]{0};
                } else {
                    allBytes[i] = items[i].serializeAsBytes();
                }
                totalLength += allBytes[i].length;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(1 + 4 + totalLength + items.length * 4);
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeByte(MAGIC_NBT);
            dos.writeInt(items.length);
            for (byte[] itemBytes : allBytes) {
                dos.writeInt(itemBytes.length);
                dos.write(itemBytes);
            }
            dos.flush();

            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            LOGGER.severe("Failed to serialize items: " + e.getMessage());
            return "";
        }
    }

    /**
     * Deserialize a Base64-encoded string back into an ItemStack array.
     * Expects the format written by {@link #serializeItems(ItemStack[])}.
     *
     * @param base64 Base64-encoded item data
     * @return deserialized ItemStack array, or empty array on failure
     */
    public static ItemStack[] deserializeItems(String base64) {
        if (base64 == null || base64.isEmpty()) return new ItemStack[0];

        try {
            byte[] allBytes = Base64.getDecoder().decode(base64);
            ByteArrayInputStream bais = new ByteArrayInputStream(allBytes);

            int magic = bais.read();
            if (magic != (MAGIC_NBT & 0xFF)) {
                LOGGER.severe("Unknown inventory data format (magic byte: 0x"
                        + Integer.toHexString(magic) + ")");
                return new ItemStack[0];
            }

            DataInputStream dis = new DataInputStream(bais);
            int itemCount = dis.readInt();
            ItemStack[] items = new ItemStack[itemCount];

            for (int i = 0; i < itemCount; i++) {
                int itemLength = dis.readInt();
                byte[] itemBytes = new byte[itemLength];
                dis.readFully(itemBytes);

                if (itemLength == 1 && itemBytes[0] == 0) {
                    items[i] = null;
                } else {
                    items[i] = ItemStack.deserializeBytes(itemBytes);
                }
            }

            return items;
        } catch (Exception e) {
            LOGGER.severe("Failed to deserialize items: " + e.getMessage());
            return new ItemStack[0];
        }
    }

    private InventorySerializer() {
        throw new UnsupportedOperationException("Utility class");
    }
}

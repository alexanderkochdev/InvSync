package dev.alexanderkoch.invsync.bukkit.sync;

import dev.alexanderkoch.invsync.api.InvSyncChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the sync rules received from Velocity.
 * <p>
 * Rules are sent by Velocity when a player joins (sync_config message)
 * and determine which data fields are applied to players on this server.
 */
public class SyncRuleManager {

    // Default: sync everything
    private volatile Map<String, Boolean> syncRules = new ConcurrentHashMap<>(InvSyncChannel.DEFAULT_SYNC_RULES);

    /** Update the cached sync rules from a Velocity sync_config message. */
    public void updateRules(Map<String, Boolean> newRules) {
        this.syncRules = new ConcurrentHashMap<>(newRules);
    }

    /** Get the current sync rules. */
    public Map<String, Boolean> getSyncRules() {
        return syncRules;
    }

    /** Check if a specific feature should be synced. */
    public boolean shouldSync(String key) {
        return syncRules.getOrDefault(key, true);
    }

    /** Get a human-readable summary of the current sync rules. */
    public String getRulesSummary() {
        return "inventory=" + shouldSync(InvSyncChannel.SYNC_INVENTORY)
                + ", ender_chest=" + shouldSync(InvSyncChannel.SYNC_ENDER_CHEST)
                + ", health=" + shouldSync(InvSyncChannel.SYNC_HEALTH)
                + ", food=" + shouldSync(InvSyncChannel.SYNC_FOOD)
                + ", experience=" + shouldSync(InvSyncChannel.SYNC_EXPERIENCE);
    }
}

package io.github.danimbrogno.regionlock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Puts players into Adventure while they stand inside an active locked zone,
 * and restores their previous game mode when they leave.
 */
public final class ZoneAdventureController {

    private final RegionLockPlugin plugin;
    private final Map<UUID, GameMode> previousModes = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> suppressingGameModeEvent = new ConcurrentHashMap<>();

    public ZoneAdventureController(RegionLockPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isManaged(UUID playerId) {
        return previousModes.containsKey(playerId);
    }

    public boolean isSuppressingGameModeEvent(UUID playerId) {
        return suppressingGameModeEvent.containsKey(playerId);
    }

    public boolean isInsideActiveLock(Location location) {
        if (location == null || location.getWorld() == null || !plugin.isProtectionActive()) {
            return false;
        }
        return plugin.zones().isLocked(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    /**
     * Sync from a movement/teleport using both endpoints so enter/leave is edge-triggered.
     */
    public void syncMove(Player player, Location from, Location to, boolean announce) {
        if (player == null || to == null) {
            return;
        }
        boolean wasInside = from != null && isInsideActiveLock(from);
        boolean nowInside = isInsideActiveLock(to);
        applyTransition(player, wasInside, nowInside, announce);
    }

    /**
     * Sync against a single location (join/respawn/reload).
     */
    public void sync(Player player, Location location, boolean announce) {
        if (player == null || location == null) {
            return;
        }
        boolean nowInside = isInsideActiveLock(location);
        boolean managed = previousModes.containsKey(player.getUniqueId());
        applyTransition(player, managed, nowInside, announce);
    }

    private void applyTransition(Player player, boolean wasInside, boolean nowInside, boolean announce) {
        UUID id = player.getUniqueId();
        boolean managed = previousModes.containsKey(id);

        if (nowInside && !wasInside) {
            enterZone(player, announce);
            return;
        }

        if (!nowInside && wasInside) {
            leaveZone(player, announce);
            return;
        }

        // Heal desyncs: managed flag should match location.
        if (nowInside && !managed) {
            enterZone(player, announce);
            return;
        }

        if (!nowInside && managed) {
            leaveZone(player, announce);
            return;
        }

        if (nowInside && player.getGameMode() != GameMode.ADVENTURE) {
            setGameModeSilently(player, GameMode.ADVENTURE);
        }
    }

    private void enterZone(Player player, boolean announce) {
        UUID id = player.getUniqueId();
        if (!previousModes.containsKey(id)) {
            previousModes.put(id, player.getGameMode());
        }
        setGameModeSilently(player, GameMode.ADVENTURE);
        if (announce) {
            sendMessage(player, plugin.zones().denyMessage());
        }
    }

    private void leaveZone(Player player, boolean announce) {
        GameMode previous = previousModes.remove(player.getUniqueId());
        if (previous != null) {
            setGameModeSilently(player, previous);
        }
        if (announce) {
            sendMessage(player, plugin.zones().leaveMessage());
        }
    }

    public void syncAllOnline(boolean announce) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sync(player, player.getLocation(), announce);
        }
    }

    /** Restore every managed player (used when protection is turned off or plugin disables). */
    public void releaseAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            release(player, false);
        }
        previousModes.clear();
    }

    public void release(Player player) {
        release(player, false);
    }

    private void release(Player player, boolean announce) {
        if (player == null) {
            return;
        }
        if (!previousModes.containsKey(player.getUniqueId())) {
            return;
        }
        leaveZone(player, announce);
    }

    private static void sendMessage(Player player, String message) {
        if (message != null && !message.isBlank()) {
            player.sendMessage(message);
        }
    }

    private void setGameModeSilently(Player player, GameMode mode) {
        UUID id = player.getUniqueId();
        suppressingGameModeEvent.put(id, Boolean.TRUE);
        try {
            if (player.getGameMode() != mode) {
                player.setGameMode(mode);
            }
        } finally {
            suppressingGameModeEvent.remove(id);
        }
    }
}

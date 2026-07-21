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
     * Sync the player's game mode with whether they are inside a locked zone.
     *
     * @param announceEnter when true and the player newly enters, send deny-message
     */
    public void sync(Player player, Location location, boolean announceEnter) {
        if (player == null) {
            return;
        }

        UUID id = player.getUniqueId();
        boolean inside = isInsideActiveLock(location);
        boolean managed = previousModes.containsKey(id);

        if (inside && !managed) {
            previousModes.put(id, player.getGameMode());
            setGameModeSilently(player, GameMode.ADVENTURE);
            if (announceEnter) {
                sendEnterMessage(player);
            }
            return;
        }

        if (inside && managed) {
            if (player.getGameMode() != GameMode.ADVENTURE) {
                setGameModeSilently(player, GameMode.ADVENTURE);
            }
            return;
        }

        if (!inside && managed) {
            GameMode previous = previousModes.remove(id);
            setGameModeSilently(player, previous != null ? previous : GameMode.SURVIVAL);
        }
    }

    public void syncAllOnline(boolean announceEnter) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sync(player, player.getLocation(), announceEnter);
        }
    }

    /** Restore every managed player (used when protection is turned off or plugin disables). */
    public void releaseAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            release(player);
        }
        previousModes.clear();
    }

    public void release(Player player) {
        if (player == null) {
            return;
        }
        GameMode previous = previousModes.remove(player.getUniqueId());
        if (previous != null && player.isOnline()) {
            setGameModeSilently(player, previous);
        }
    }

    private void sendEnterMessage(Player player) {
        String message = plugin.zones().denyMessage();
        if (!message.isBlank()) {
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

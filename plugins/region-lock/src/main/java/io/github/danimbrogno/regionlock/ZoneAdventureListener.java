package io.github.danimbrogno.regionlock;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class ZoneAdventureListener implements Listener {

    private final RegionLockPlugin plugin;
    private final ZoneAdventureController adventure;

    public ZoneAdventureListener(RegionLockPlugin plugin, ZoneAdventureController adventure) {
        this.plugin = plugin;
        this.adventure = adventure;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // PlayerTeleportEvent extends PlayerMoveEvent — handled in onTeleport to avoid double announce.
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() != null
                && from.getWorld().equals(to.getWorld())) {
            return;
        }
        adventure.syncMove(event.getPlayer(), from, to, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        adventure.syncMove(event.getPlayer(), event.getFrom(), to, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                adventure.sync(player, player.getLocation(), true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location respawn = event.getRespawnLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                adventure.sync(player, respawn, true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Restore saved mode so Adventure is not written as their persistent mode.
        adventure.release(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (adventure.isSuppressingGameModeEvent(player.getUniqueId())) {
            return;
        }
        if (!adventure.isInsideActiveLock(player.getLocation())) {
            return;
        }
        if (event.getNewGameMode() == GameMode.ADVENTURE) {
            return;
        }
        // Keep them in Adventure while standing in a locked zone.
        event.setCancelled(true);
    }
}

package io.github.danimbrogno.regionlock;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Backup enforcement for place/break. Primary protection is Adventure mode inside zones.
 * Deny messages are sent on zone enter, not on each click.
 */
public final class BlockLockListener implements Listener {

    private final RegionLockPlugin plugin;

    public BlockLockListener(RegionLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isLocked(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        event.setDropItems(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isLocked(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean isLocked(Block block) {
        if (block == null || !plugin.isProtectionActive()) {
            return false;
        }
        return plugin.zones().isLocked(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }
}

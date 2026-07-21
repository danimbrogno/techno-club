package io.github.danimbrogno.regionlock;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public final class BlockLockListener implements Listener {

    private final RegionLockPlugin plugin;

    public BlockLockListener(RegionLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        denyIfLocked(event.getPlayer(), event.getBlock(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        denyIfLocked(event.getPlayer(), event.getBlock(), () -> event.setCancelled(true));
    }

    private void denyIfLocked(Player player, Block block, Runnable cancel) {
        ZoneRepository zones = plugin.zones();
        String world = block.getWorld().getName();
        if (!zones.isLocked(world, block.getX(), block.getY(), block.getZ())) {
            return;
        }

        cancel.run();
        String message = zones.denyMessage();
        if (!message.isBlank()) {
            player.sendMessage(message);
        }
    }
}

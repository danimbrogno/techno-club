package io.github.danimbrogno.regionlock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class BlockLockListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 750L;

    private final RegionLockPlugin plugin;
    private final Map<UUID, Long> lastDenyMessageAt = new ConcurrentHashMap<>();

    public BlockLockListener(RegionLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isLocked(block)) {
            return;
        }

        // Snapshot before anything else mutates the world (Geyser/other plugins may ignore cancel).
        BlockData snapshot = block.getBlockData().clone();
        event.setCancelled(true);
        event.setDropItems(false);
        notifyDenied(event.getPlayer());
        restoreNextTick(block, snapshot);
    }

    /**
     * Cancels the dig start so survival (and some Geyser/Bedrock) breaks cannot finish.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (!isLocked(block)) {
            return;
        }
        BlockData snapshot = block.getBlockData().clone();
        event.setCancelled(true);
        notifyDenied(event.getPlayer());
        restoreNextTick(block, snapshot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isLocked(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        notifyDenied(event.getPlayer());
    }

    /**
     * Extra guard for left-click break attempts (helps some Bedrock/Geyser clients).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeftClickBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isLocked(block)) {
            return;
        }
        BlockData snapshot = block.getBlockData().clone();
        event.setCancelled(true);
        notifyDenied(event.getPlayer());
        restoreNextTick(block, snapshot);
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

    private void notifyDenied(Player player) {
        if (player == null) {
            return;
        }
        String message = plugin.zones().denyMessage();
        if (message.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastDenyMessageAt.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastDenyMessageAt.put(player.getUniqueId(), now);
        player.sendMessage(message);
    }

    private void restoreNextTick(Block block, BlockData snapshot) {
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isProtectionActive() || !plugin.zones().isLocked(worldName, x, y, z)) {
                return;
            }
            Block current = block.getWorld().getBlockAt(x, y, z);
            if (!current.getBlockData().equals(snapshot)) {
                current.setBlockData(snapshot, false);
            }
        });
    }
}

package io.github.danimbrogno.regionlock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.Block;
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

/**
 * Backup enforcement for place/break. Primary protection is Adventure mode inside zones.
 * Deny message is also sent on zone enter ({@link ZoneAdventureController}).
 */
public final class BlockLockListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 750L;

    private final RegionLockPlugin plugin;
    private final Map<UUID, Long> lastDenyMessageAt = new ConcurrentHashMap<>();

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
        notifyDenied(event.getPlayer());
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
     * Adventure mode often never fires {@link BlockBreakEvent}; dig attempts still hit damage/interact.
     * Message only — do not cancel damage (Paper can leave bad client block state).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!isLocked(event.getBlock())) {
            return;
        }
        notifyDenied(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeftClickBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!isLocked(block)) {
            return;
        }
        notifyDenied(event.getPlayer());
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
}

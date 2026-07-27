package io.github.danimbrogno.lessonportal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Keeps tagged lesson books inside the bound hub. No-ops entirely when the hub bound box is
 * unset ({@link LessonPortalPlugin#reloadAll()} logs that condition once per reload).
 */
public final class BookGuardListener implements Listener {

    private final LessonPortalPlugin plugin;
    private final Map<UUID, List<String>> lostLessonIds = new ConcurrentHashMap<>();

    public BookGuardListener(LessonPortalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Optional<BoundBox> hub = plugin.layout().hub();
        if (hub.isEmpty()) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !changedBlockPosition(from, to)) {
            return;
        }

        boolean wasInside = containsLocation(hub.get(), from);
        boolean isInside = containsLocation(hub.get(), to);
        if (!wasInside || isInside) {
            return;
        }

        if (stripLessonBooks(event.getPlayer().getInventory())) {
            plugin.shelfService().repair();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Optional<BoundBox> hub = plugin.layout().hub();
        if (hub.isEmpty()) {
            return;
        }

        Item drop = event.getItemDrop();
        if (!plugin.books().isLessonBook(drop.getItemStack())) {
            return;
        }

        if (containsLocation(hub.get(), event.getPlayer().getLocation())) {
            event.setCancelled(true);
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (drop.isValid()) {
                drop.remove();
            }
            plugin.shelfService().repair();
        });
    }

    /**
     * {@link EntityDamageEvent} and {@link ItemDespawnEvent} are always cancellable, so cancelling
     * here is sufficient; there is no case that needs a shelf-repair fallback for these two events.
     */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (plugin.layout().hub().isEmpty()) {
            return;
        }
        if (event.getEntity() instanceof Item item && plugin.books().isLessonBook(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (plugin.layout().hub().isEmpty()) {
            return;
        }
        if (plugin.books().isLessonBook(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (plugin.layout().hub().isEmpty()) {
            return;
        }
        if (!plugin.books().isLessonBook(event.getItem())) {
            return;
        }
        if (isBoundLecternOrShelf(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.layout().hub().isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        List<String> lessonIds = collectLessonIds(player.getInventory());
        if (!lessonIds.isEmpty()) {
            lostLessonIds.put(player.getUniqueId(), lessonIds);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.layout().hub().isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        List<String> lostIds = lostLessonIds.remove(player.getUniqueId());
        if (lostIds == null || lostIds.isEmpty()) {
            return;
        }

        Set<String> present = new HashSet<>(collectLessonIds(player.getInventory()));
        for (String id : lostIds) {
            if (present.contains(id)) {
                continue;
            }
            plugin.catalog().get(id).ifPresent(lesson -> player.getInventory().addItem(plugin.books().create(lesson)));
        }
        plugin.shelfService().repair();
    }

    private boolean stripLessonBooks(PlayerInventory inventory) {
        boolean changed = false;

        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (plugin.books().isLessonBook(storage[i])) {
                storage[i] = null;
                changed = true;
            }
        }
        inventory.setStorageContents(storage);

        ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (plugin.books().isLessonBook(armor[i])) {
                armor[i] = null;
                changed = true;
            }
        }
        inventory.setArmorContents(armor);

        if (plugin.books().isLessonBook(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(null);
            changed = true;
        }

        return changed;
    }

    private List<String> collectLessonIds(PlayerInventory inventory) {
        List<String> ids = new ArrayList<>();
        for (ItemStack stack : inventory.getStorageContents()) {
            plugin.books().readLessonId(stack).ifPresent(ids::add);
        }
        for (ItemStack stack : inventory.getArmorContents()) {
            plugin.books().readLessonId(stack).ifPresent(ids::add);
        }
        plugin.books().readLessonId(inventory.getItemInOffHand()).ifPresent(ids::add);
        return ids;
    }

    private boolean isBoundLecternOrShelf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof BlockInventoryHolder blockHolder)) {
            return false;
        }
        Block block = blockHolder.getBlock();
        if (block.getType() == Material.LECTERN) {
            return plugin.isBoundLectern(block);
        }
        if (block.getType() == Material.CHISELED_BOOKSHELF) {
            return plugin.isBoundShelf(block);
        }
        return false;
    }

    private static boolean containsLocation(BoundBox box, Location location) {
        return location.getWorld() != null
                && box.contains(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static boolean changedBlockPosition(Location from, Location to) {
        return from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()
                || !worldsEqual(from.getWorld(), to.getWorld());
    }

    private static boolean worldsEqual(World a, World b) {
        return a != null && a.equals(b);
    }
}

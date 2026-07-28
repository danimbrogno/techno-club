package io.github.danimbrogno.lessonportal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

    /**
     * {@link PlayerTeleportEvent} is fired separately from {@link PlayerMoveEvent} (Bukkit does not
     * dispatch move handlers for teleports even though the event extends it), so portal-driven
     * teleports out of the hub (e.g. {@link PortalListener}) need their own strip check here.
     * Any teleport landing outside the hub is treated the same whether it started inside the hub
     * or not, since a player already outside the hub should never be holding tagged books.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Optional<BoundBox> hub = plugin.layout().hub();
        if (hub.isEmpty()) {
            return;
        }

        Location to = event.getTo();
        if (to == null || to.getWorld() == null || containsLocation(hub.get(), to)) {
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

        boolean playerInside = containsLocation(hub.get(), event.getPlayer().getLocation());
        boolean dropInside = containsLocation(hub.get(), drop.getLocation());
        if (playerInside && dropInside) {
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

    /**
     * Blocks hopper/hopper-minecart vacuuming of tagged books out of bound shelves or lecterns;
     * without this a hopper could suck a book out from underneath {@link #onInventoryMoveItem}'s
     * container-to-container guard.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (plugin.layout().hub().isEmpty()) {
            return;
        }
        if (plugin.books().isLessonBook(event.getItem().getItemStack())) {
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

    /**
     * Tagged books are made immortal on the ground (see {@link #onItemDamage} and
     * {@link #onItemDespawn}), so any that end up in {@link PlayerDeathEvent#getDrops()} must be
     * stripped here or they would linger forever as indestructible ground items. Shelf repair
     * (triggered on respawn) recreates them.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Optional<BoundBox> hub = plugin.layout().hub();
        if (hub.isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        if (!containsLocation(hub.get(), player.getLocation())) {
            return;
        }
        List<String> lessonIds = collectLessonIds(player.getInventory());
        if (!lessonIds.isEmpty()) {
            lostLessonIds.put(player.getUniqueId(), lessonIds);
        }
        event.getDrops().removeIf(plugin.books()::isLessonBook);
    }

    /**
     * Only restores books directly into the player's inventory when they respawn inside the hub;
     * a respawn elsewhere (e.g. a bed set up outside the hub) must not hand out tagged books that
     * could then leave the hub. Shelf repair still runs either way so the books are always
     * available again on the shelves.
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.layout().hub().isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        List<String> lostIds = lostLessonIds.remove(player.getUniqueId());
        if (lostIds == null || lostIds.isEmpty()) {
            plugin.shelfService().repair();
            return;
        }

        Optional<BoundBox> hub = plugin.layout().hub();
        if (hub.isEmpty() || !containsLocation(hub.get(), event.getRespawnLocation())) {
            plugin.shelfService().repair();
            return;
        }

        Map<String, Long> expectedCounts = lostIds.stream()
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        Map<String, Long> presentCounts = collectLessonIds(player.getInventory()).stream()
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        for (Map.Entry<String, Long> entry : expectedCounts.entrySet()) {
            long missing = entry.getValue() - presentCounts.getOrDefault(entry.getKey(), 0L);
            if (missing <= 0) {
                continue;
            }
            plugin.catalog().get(entry.getKey()).ifPresent(lesson -> {
                for (int i = 0; i < missing; i++) {
                    player.getInventory().addItem(plugin.books().create(lesson));
                }
            });
        }
        plugin.shelfService().repair();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lostLessonIds.remove(event.getPlayer().getUniqueId());
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

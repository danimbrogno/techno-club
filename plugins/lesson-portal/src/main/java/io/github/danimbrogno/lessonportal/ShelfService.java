package io.github.danimbrogno.lessonportal;

import java.util.List;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ShelfService {

    private final LessonPortalPlugin plugin;

    public ShelfService(LessonPortalPlugin plugin) {
        this.plugin = plugin;
    }

    public void repair() {
        HubLayout layout = plugin.layout();
        LessonCatalog catalog = plugin.catalog();
        List<String> lessonIds = catalog.lessons().stream().map(LessonDefinition::id).toList();
        List<ShelfPlanner.ShelfSlot> plan = ShelfPlanner.plan(layout.shelves(), lessonIds);

        int capacity = layout.shelves().size() * ShelfPlanner.SLOTS_PER_SHELF;
        if (lessonIds.size() > capacity) {
            plugin.getLogger().warning(
                    "Lesson catalog has " + lessonIds.size() + " lesson(s) but only " + capacity
                            + " bound shelf slot(s); extra lessons will not appear on shelves.");
        }

        for (ShelfPlanner.ShelfSlot slot : plan) {
            repairSlot(slot, catalog);
        }
    }

    private void repairSlot(ShelfPlanner.ShelfSlot slot, LessonCatalog catalog) {
        Optional<LessonDefinition> lesson = catalog.get(slot.lessonId());
        if (lesson.isEmpty()) {
            return;
        }

        BlockPos pos = slot.shelf();
        World world = plugin.getServer().getWorld(pos.world());
        if (world == null) {
            plugin.getLogger().warning("Skipping shelf repair; world not loaded: " + pos.world());
            return;
        }

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (block.getType() != Material.CHISELED_BOOKSHELF) {
            plugin.getLogger().warning("Skipping shelf repair; block at " + describe(pos) + " is not a chiseled bookshelf.");
            return;
        }

        BlockState state = block.getState(false);
        if (!(state instanceof ChiseledBookshelf shelf)) {
            plugin.getLogger().warning("Skipping shelf repair; block state at " + describe(pos) + " is not a chiseled bookshelf.");
            return;
        }

        Inventory inventory = shelf.getInventory();
        ItemStack current = inventory.getItem(slot.slot());
        boolean matches = plugin.books().readLessonId(current)
                .map(id -> id.equals(slot.lessonId()))
                .orElse(false);
        if (!matches) {
            inventory.setItem(slot.slot(), plugin.books().create(lesson.get()));
        }
    }

    private static String describe(BlockPos pos) {
        return pos.world() + " (" + pos.x() + "," + pos.y() + "," + pos.z() + ")";
    }
}

package io.github.danimbrogno.lessonportal;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lectern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import net.kyori.adventure.text.Component;

/**
 * Admin bind/reload/status commands for Lesson Portal. Every subcommand that mutates
 * {@code layout.yml} follows the same pattern: build the new {@link HubLayout}, write it via
 * {@link LayoutIO#save}, then call {@link LessonPortalPlugin#applyLayoutAndRepair()} so the
 * in-memory layout and bookshelves stay in sync with disk.
 */
public final class LessonPortalCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "lessonportal.admin";
    private static final List<String> SUBCOMMANDS = List.of(
            "pos1", "pos2", "sethub", "setportal", "setlectern", "addshelf",
            "clearshelves", "reload", "status", "clear");
    private static final double SELECTION_RAY_DISTANCE = 100.0;

    private final LessonPortalPlugin plugin;

    public LessonPortalCommand(LessonPortalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("You do not have permission to use Lesson Portal commands.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pos1" -> handlePos(sender, true);
            case "pos2" -> handlePos(sender, false);
            case "sethub" -> handleSetHub(sender);
            case "setportal" -> handleSetPortal(sender);
            case "setlectern" -> handleSetLectern(sender);
            case "addshelf" -> handleAddShelf(sender);
            case "clearshelves" -> handleClearShelves(sender);
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "clear" -> handleClear();
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void handlePos(CommandSender sender, boolean first) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        BlockPos pos = toBlockPos(resolveTargetBlock(player));
        if (first) {
            plugin.selection().setPos1(player.getUniqueId(), pos);
            sender.sendMessage("pos1 set to " + describe(pos));
        } else {
            plugin.selection().setPos2(player.getUniqueId(), pos);
            sender.sendMessage("pos2 set to " + describe(pos));
        }
    }

    private void handleSetHub(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        Optional<BoundBox> box = selectionBox(sender, player.getUniqueId());
        if (box.isEmpty()) {
            return;
        }
        if (saveAndApply(sender, plugin.layout().withHub(box.get()))) {
            sender.sendMessage("Hub bound: " + box.get().describe());
        }
    }

    private void handleSetPortal(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        Optional<BoundBox> box = selectionBox(sender, player.getUniqueId());
        if (box.isEmpty()) {
            return;
        }
        if (saveAndApply(sender, plugin.layout().withPortal(box.get()))) {
            sender.sendMessage("Portal bound: " + box.get().describe());
        }
    }

    private void handleSetLectern(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        Block target = resolveTargetBlock(player);
        if (target.getType() != Material.LECTERN) {
            sender.sendMessage("Target block must be a lectern (found " + target.getType() + ").");
            return;
        }
        BlockPos pos = toBlockPos(target);
        if (saveAndApply(sender, plugin.layout().withLectern(pos))) {
            sender.sendMessage("Lectern bound at " + describe(pos));
        }
    }

    private void handleAddShelf(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        Block target = resolveTargetBlock(player);
        if (target.getType() != Material.CHISELED_BOOKSHELF) {
            sender.sendMessage("Target block must be a chiseled bookshelf (found " + target.getType() + ").");
            return;
        }
        BlockPos pos = toBlockPos(target);
        List<BlockPos> shelves = new ArrayList<>(plugin.layout().shelves());
        shelves.add(pos);
        if (saveAndApply(sender, plugin.layout().withShelves(shelves))) {
            sender.sendMessage("Shelf added at " + describe(pos) + " (" + shelves.size() + " total).");
        }
    }

    private void handleClearShelves(CommandSender sender) {
        if (saveAndApply(sender, plugin.layout().withShelves(List.of()))) {
            sender.sendMessage("All bound shelves cleared.");
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadAll();
        sender.sendMessage("Lesson Portal config and layout reloaded.");
    }

    private void handleStatus(CommandSender sender) {
        HubLayout layout = plugin.layout();
        sender.sendMessage("Lesson Portal status:");
        sender.sendMessage("- Active lesson: " + activeLessonSummary());
        sender.sendMessage("- Hub: " + layout.hub().map(BoundBox::describe).orElse("not set"));
        sender.sendMessage("- Lectern: " + layout.lectern()
                .map(pos -> describe(pos) + " [" + blockCheck(pos, Material.LECTERN) + "]")
                .orElse("not set"));
        sender.sendMessage("- Shelves: " + shelvesSummary(layout));
        sender.sendMessage("- Portal: " + layout.portal().map(BoundBox::describe).orElse("not set"));
        sender.sendMessage("- Layout complete: " + (layout.isComplete() ? "yes" : "no"));
    }

    private void handleClear() {
        ejectLecternBook();
        plugin.store().clear();
        plugin.getServer().broadcast(Component.text(plugin.catalog().message("portal-cleared")));
    }

    private void ejectLecternBook() {
        Optional<BlockPos> lecternPos = plugin.layout().lectern();
        if (lecternPos.isEmpty()) {
            return;
        }
        BlockPos pos = lecternPos.get();
        World world = plugin.getServer().getWorld(pos.world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (block.getType() != Material.LECTERN) {
            return;
        }
        BlockState state = block.getState(false);
        if (!(state instanceof Lectern lectern)) {
            return;
        }
        ItemStack book = lectern.getInventory().getItem(0);
        if (book == null) {
            return;
        }
        world.dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), book);
        lectern.getInventory().setItem(0, null);
    }

    private Optional<BoundBox> selectionBox(CommandSender sender, UUID player) {
        Optional<BlockPos> pos1 = plugin.selection().pos1(player);
        Optional<BlockPos> pos2 = plugin.selection().pos2(player);
        if (pos1.isEmpty() || pos2.isEmpty()) {
            sender.sendMessage("Set both pos1 and pos2 first.");
            return Optional.empty();
        }
        if (!pos1.get().world().equals(pos2.get().world())) {
            sender.sendMessage("pos1 and pos2 must be in the same world.");
            return Optional.empty();
        }
        BlockPos a = pos1.get();
        BlockPos b = pos2.get();
        return Optional.of(BoundBox.of(a.world(), a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
    }

    private boolean saveAndApply(CommandSender sender, HubLayout newLayout) {
        try {
            LayoutIO.save(plugin.layoutFile(), newLayout);
        } catch (UncheckedIOException e) {
            sender.sendMessage("Failed to save layout.yml: " + e.getCause().getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to save layout.yml", e.getCause());
            return false;
        }
        plugin.applyLayoutAndRepair();
        return true;
    }

    private String activeLessonSummary() {
        return plugin.store().current()
                .map(id -> plugin.catalog().get(id)
                        .map(lesson -> id + " (" + lesson.title() + ")")
                        .orElse(id + " (unknown lesson)"))
                .orElse("none");
    }

    private String shelvesSummary(HubLayout layout) {
        List<BlockPos> shelves = layout.shelves();
        if (shelves.isEmpty()) {
            return "none";
        }
        long ok = shelves.stream()
                .filter(pos -> "OK".equals(blockCheck(pos, Material.CHISELED_BOOKSHELF)))
                .count();
        return shelves.size() + " bound (" + ok + " OK, " + (shelves.size() - ok) + " need attention)";
    }

    private String blockCheck(BlockPos pos, Material expected) {
        World world = plugin.getServer().getWorld(pos.world());
        if (world == null) {
            return "world not loaded";
        }
        Material actual = world.getBlockAt(pos.x(), pos.y(), pos.z()).getType();
        return actual == expected ? "OK" : "wrong block: " + actual;
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("This subcommand must be run in-game.");
        return null;
    }

    private static Block resolveTargetBlock(Player player) {
        RayTraceResult result = player.rayTraceBlocks(SELECTION_RAY_DISTANCE);
        Block hit = result != null ? result.getHitBlock() : null;
        return hit != null ? hit : player.getLocation().getBlock();
    }

    private static BlockPos toBlockPos(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String describe(BlockPos pos) {
        return pos.world() + " (" + pos.x() + "," + pos.y() + "," + pos.z() + ")";
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("Usage: /" + label + " <" + String.join("|", SUBCOMMANDS) + ">");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1) {
            return List.of();
        }
        return filter(SUBCOMMANDS, args[0]);
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.startsWith(lower))
                .sorted()
                .toList();
    }
}

package io.github.danimbrogno.lessonportal;

import java.io.File;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LessonPortalPlugin extends JavaPlugin {

    private LessonCatalog catalog = LessonCatalog.fromEntries(
            Map.of(), Map.of(), Logger.getLogger(LessonPortalPlugin.class.getName()));
    private HubLayout layout = HubLayout.empty();
    private ActiveLessonStore store;
    private BookFactory books;
    private ShelfService shelfService;
    private final SelectionSession selection = new SelectionSession();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "layout.yml").exists()) {
            saveResource("layout.yml", false);
        }

        books = new BookFactory(this);
        shelfService = new ShelfService(this);
        store = new ActiveLessonStore(new File(getDataFolder(), "active-lesson.yml").toPath());
        store.load();

        reloadAll();

        store.current().ifPresent(id -> getLogger().info("Active lesson on startup: " + id));

        getServer().getPluginManager().registerEvents(new LecternListener(this), this);
        getServer().getPluginManager().registerEvents(new PortalListener(this), this);
        getServer().getPluginManager().registerEvents(new BookGuardListener(this), this);
        getServer().getPluginManager().registerEvents(selection, this);

        LessonPortalCommand command = new LessonPortalCommand(this);
        PluginCommand pluginCommand = getCommand("lessonportal");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'lessonportal' missing from plugin.yml");
        }

        getLogger().info("LessonPortal enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("LessonPortal disabled!");
    }

    public void reloadAll() {
        reloadConfig();
        catalog = LessonCatalog.load(getConfig(), getLogger());
        store.retainIfKnown(catalog.lessons().stream()
                .map(LessonDefinition::id)
                .collect(Collectors.toUnmodifiableSet()));
        applyLayoutAndRepair();
    }

    /**
     * Re-reads {@code layout.yml} from disk and repairs shelves against it. Used both by
     * {@link #reloadAll()} and by {@link LessonPortalCommand} after every layout-mutating
     * subcommand writes a fresh {@code layout.yml} via {@link LayoutIO#save}.
     */
    public void applyLayoutAndRepair() {
        layout = LayoutIO.load(layoutFile(), getLogger());
        shelfService.repair();
        if (layout.hub().isEmpty()) {
            getLogger().warning("Book guard disabled: hub bounds not configured.");
        }
    }

    public File layoutFile() {
        return new File(getDataFolder(), "layout.yml");
    }

    public LessonCatalog catalog() {
        return catalog;
    }

    public HubLayout layout() {
        return layout;
    }

    public ActiveLessonStore store() {
        return store;
    }

    public BookFactory books() {
        return books;
    }

    public ShelfService shelfService() {
        return shelfService;
    }

    public SelectionSession selection() {
        return selection;
    }

    public boolean isBoundLectern(Block block) {
        return layout.lectern()
                .map(pos -> pos.world().equals(block.getWorld().getName())
                        && pos.x() == block.getX()
                        && pos.y() == block.getY()
                        && pos.z() == block.getZ())
                .orElse(false);
    }

    public boolean isBoundShelf(Block block) {
        return layout.shelves().stream().anyMatch(pos -> pos.world().equals(block.getWorld().getName())
                && pos.x() == block.getX()
                && pos.y() == block.getY()
                && pos.z() == block.getZ());
    }
}

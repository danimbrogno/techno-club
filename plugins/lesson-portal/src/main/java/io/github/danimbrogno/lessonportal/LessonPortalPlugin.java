package io.github.danimbrogno.lessonportal;

import java.io.File;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

public final class LessonPortalPlugin extends JavaPlugin {

    private LessonCatalog catalog = LessonCatalog.fromEntries(
            Map.of(), Map.of(), Logger.getLogger(LessonPortalPlugin.class.getName()));
    private HubLayout layout = HubLayout.empty();
    private ActiveLessonStore store;
    private BookFactory books;
    private ShelfService shelfService;

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

        getLogger().info("LessonPortal enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("LessonPortal disabled!");
    }

    public void reloadAll() {
        reloadConfig();
        catalog = LessonCatalog.load(getConfig(), getLogger());
        layout = LayoutIO.load(new File(getDataFolder(), "layout.yml"), getLogger());
        store.retainIfKnown(catalog.lessons().stream()
                .map(LessonDefinition::id)
                .collect(Collectors.toUnmodifiableSet()));
        shelfService.repair();
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

    public boolean isBoundLectern(Block block) {
        return layout.lectern()
                .map(pos -> pos.world().equals(block.getWorld().getName())
                        && pos.x() == block.getX()
                        && pos.y() == block.getY()
                        && pos.z() == block.getZ())
                .orElse(false);
    }
}

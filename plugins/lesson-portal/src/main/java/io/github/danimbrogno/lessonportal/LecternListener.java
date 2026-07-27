package io.github.danimbrogno.lessonportal;

import java.util.Optional;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import net.kyori.adventure.text.Component;

public final class LecternListener implements Listener {

    private final LessonPortalPlugin plugin;

    public LecternListener(LessonPortalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInsertBook(PlayerInsertLecternBookEvent event) {
        if (!plugin.isBoundLectern(event.getBlock())) {
            return;
        }

        Optional<String> lessonId = plugin.books().readLessonId(event.getBook());
        if (lessonId.isEmpty()) {
            event.setCancelled(true);
            return;
        }

        Optional<LessonDefinition> lesson = plugin.catalog().get(lessonId.get());
        if (lesson.isEmpty()) {
            event.setCancelled(true);
            return;
        }

        plugin.store().set(lesson.get().id());
        String message = plugin.catalog().message("portal-set").replace("{title}", lesson.get().title());
        plugin.getServer().broadcast(Component.text(message));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTakeBook(PlayerTakeLecternBookEvent event) {
        if (!plugin.isBoundLectern(event.getLectern().getBlock())) {
            return;
        }

        plugin.store().clear();
        String message = plugin.catalog().message("portal-cleared");
        plugin.getServer().broadcast(Component.text(message));
    }
}

package io.github.danimbrogno.lessonportal;

import java.util.Optional;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import net.kyori.adventure.text.Component;

public final class LecternListener implements Listener {

    private final LessonPortalPlugin plugin;

    public LecternListener(LessonPortalPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs at NORMAL priority (default, cancellable) purely to reject unknown/uncatalogued
     * books before other plugins act on the event. Must not touch {@link ActiveLessonStore}
     * here: a later listener could still cancel the event, which would desync the store from
     * the lectern's actual contents.
     */
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
        }
    }

    /**
     * Runs at MONITOR, after every other listener has had a chance to cancel the event.
     * Only here do we mutate the store, and only if the event survived uncancelled — this
     * keeps {@link ActiveLessonStore} in sync with what actually ended up in the lectern.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInsertBookMonitor(PlayerInsertLecternBookEvent event) {
        if (!plugin.isBoundLectern(event.getBlock())) {
            return;
        }

        Optional<String> lessonId = plugin.books().readLessonId(event.getBook());
        if (lessonId.isEmpty()) {
            return;
        }

        Optional<LessonDefinition> lesson = plugin.catalog().get(lessonId.get());
        if (lesson.isEmpty()) {
            return;
        }

        plugin.store().set(lesson.get().id());
        String message = plugin.catalog().message("portal-set").replace("{title}", lesson.get().title());
        plugin.getServer().broadcast(Component.text(message));
    }

    /**
     * MONITOR + ignoreCancelled so the store is only cleared once the take is final and no
     * other listener vetoed it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeBook(PlayerTakeLecternBookEvent event) {
        if (!plugin.isBoundLectern(event.getLectern().getBlock())) {
            return;
        }

        plugin.store().clear();
        String message = plugin.catalog().message("portal-cleared");
        plugin.getServer().broadcast(Component.text(message));
    }
}

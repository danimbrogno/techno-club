package io.github.danimbrogno.lessonportal;

import org.bukkit.plugin.java.JavaPlugin;

public final class LessonPortalPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("LessonPortal enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("LessonPortal disabled!");
    }
}

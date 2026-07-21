package io.github.danimbrogno.starterlesson;

import org.bukkit.plugin.java.JavaPlugin;

public final class StarterLessonPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("StarterLesson enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("StarterLesson disabled!");
    }
}

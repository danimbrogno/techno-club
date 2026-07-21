package io.github.danimbrogno.clubsupport;

import org.bukkit.plugin.java.JavaPlugin;

public final class ClubSupportPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ClubSupport enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("ClubSupport disabled!");
    }
}

package io.github.danimbrogno.regionlock;

import org.bukkit.plugin.java.JavaPlugin;

public final class RegionLockPlugin extends JavaPlugin {

    private ZoneRepository zones = ZoneRepository.empty();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadZones();
        getServer().getPluginManager().registerEvents(new BlockLockListener(this), this);
        getLogger().info("RegionLock enabled with " + zones.zones().size() + " zone(s).");
    }

    @Override
    public void onDisable() {
        getLogger().info("RegionLock disabled!");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        reloadZones();
    }

    void reloadZones() {
        zones = ZoneRepository.load(getConfig(), getLogger());
    }

    ZoneRepository zones() {
        return zones;
    }
}

package io.github.danimbrogno.regionlock;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RegionLockPlugin extends JavaPlugin {

    private ZoneRepository zones = ZoneRepository.empty();
    private boolean protectionActive = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadZones();
        getServer().getPluginManager().registerEvents(new BlockLockListener(this), this);

        RegionLockCommand command = new RegionLockCommand(this);
        PluginCommand pluginCommand = getCommand("regionlock");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'regionlock' missing from plugin.yml");
        }

        getLogger().info("RegionLock enabled with " + zones.zones().size() + " zone(s). Global: "
                + (protectionActive ? "ON" : "OFF"));
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
        protectionActive = getConfig().getBoolean("enabled", true);
    }

    boolean isProtectionActive() {
        return protectionActive;
    }

    void setProtectionActive(boolean active) {
        protectionActive = active;
        getConfig().set("enabled", active);
        saveConfig();
    }

    void setZoneEnabled(String zoneName, boolean enabled) {
        zones.findByName(zoneName).ifPresent(zone -> {
            zone.setEnabled(enabled);
            getConfig().set("zones." + zone.name() + ".enabled", enabled);
            saveConfig();
        });
    }

    ZoneRepository zones() {
        return zones;
    }
}

package io.github.danimbrogno.regionlock;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RegionLockPlugin extends JavaPlugin {

    private ZoneRepository zones = ZoneRepository.empty();
    private boolean protectionActive = true;
    private ZoneAdventureController adventure;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        adventure = new ZoneAdventureController(this);
        reloadZones();
        getServer().getPluginManager().registerEvents(new BlockLockListener(this), this);
        getServer().getPluginManager().registerEvents(new ZoneAdventureListener(this, adventure), this);

        RegionLockCommand command = new RegionLockCommand(this);
        PluginCommand pluginCommand = getCommand("regionlock");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'regionlock' missing from plugin.yml");
        }

        // Players already standing in a zone when the plugin enables.
        getServer().getScheduler().runTask(this, () -> adventure.syncAllOnline(true));

        getLogger().info("RegionLock enabled with " + zones.zones().size() + " zone(s). Global: "
                + (protectionActive ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        if (adventure != null) {
            adventure.releaseAll();
        }
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
        if (adventure != null) {
            if (protectionActive) {
                adventure.syncAllOnline(false);
            } else {
                adventure.releaseAll();
            }
        }
    }

    boolean isProtectionActive() {
        return protectionActive;
    }

    void setProtectionActive(boolean active) {
        protectionActive = active;
        getConfig().set("enabled", active);
        saveConfig();
        if (adventure == null) {
            return;
        }
        if (active) {
            adventure.syncAllOnline(true);
        } else {
            adventure.releaseAll();
        }
    }

    void setZoneEnabled(String zoneName, boolean enabled) {
        zones.findByName(zoneName).ifPresent(zone -> {
            zone.setEnabled(enabled);
            getConfig().set("zones." + zone.name() + ".enabled", enabled);
            saveConfig();
            if (adventure != null) {
                adventure.syncAllOnline(enabled);
            }
        });
    }

    ZoneRepository zones() {
        return zones;
    }

    ZoneAdventureController adventure() {
        return adventure;
    }
}

package io.github.danimbrogno.regionlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class ZoneRepository {

    private final List<LockedZone> zones;
    private final String denyMessage;

    private ZoneRepository(List<LockedZone> zones, String denyMessage) {
        this.zones = List.copyOf(zones);
        this.denyMessage = denyMessage == null ? "" : denyMessage;
    }

    public static ZoneRepository empty() {
        return new ZoneRepository(List.of(), "");
    }

    public static ZoneRepository load(FileConfiguration config, Logger logger) {
        String denyMessage = config.getString("deny-message", "This area is locked.");
        ConfigurationSection zonesSection = config.getConfigurationSection("zones");
        if (zonesSection == null) {
            return new ZoneRepository(List.of(), denyMessage);
        }

        List<LockedZone> loaded = new ArrayList<>();
        for (String name : zonesSection.getKeys(false)) {
            ConfigurationSection zone = zonesSection.getConfigurationSection(name);
            if (zone == null) {
                logger.warning("Skipping zone '" + name + "': expected a map section.");
                continue;
            }

            String world = zone.getString("world");
            if (world == null || world.isBlank()) {
                logger.warning("Skipping zone '" + name + "': missing world.");
                continue;
            }

            Integer minX = readCoord(zone, "min", "x");
            Integer minY = readCoord(zone, "min", "y");
            Integer minZ = readCoord(zone, "min", "z");
            Integer maxX = readCoord(zone, "max", "x");
            Integer maxY = readCoord(zone, "max", "y");
            Integer maxZ = readCoord(zone, "max", "z");

            if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) {
                logger.warning("Skipping zone '" + name + "': min/max must include numeric x, y, z.");
                continue;
            }

            loaded.add(LockedZone.of(name, world, minX, minY, minZ, maxX, maxY, maxZ));
        }

        return new ZoneRepository(loaded, denyMessage);
    }

    /**
     * Test-friendly loader that does not require Bukkit configuration objects.
     */
    static ZoneRepository fromEntries(Map<String, ZoneEntry> entries, String denyMessage, Logger logger) {
        List<LockedZone> loaded = new ArrayList<>();
        for (Map.Entry<String, ZoneEntry> entry : entries.entrySet()) {
            String name = entry.getKey();
            ZoneEntry zone = entry.getValue();
            if (zone.world() == null || zone.world().isBlank()) {
                logger.warning("Skipping zone '" + name + "': missing world.");
                continue;
            }
            loaded.add(LockedZone.of(
                    name,
                    zone.world(),
                    zone.minX(),
                    zone.minY(),
                    zone.minZ(),
                    zone.maxX(),
                    zone.maxY(),
                    zone.maxZ()
            ));
        }
        return new ZoneRepository(loaded, denyMessage);
    }

    public List<LockedZone> zones() {
        return zones;
    }

    public String denyMessage() {
        return denyMessage;
    }

    public boolean isLocked(String world, int x, int y, int z) {
        for (LockedZone zone : zones) {
            if (zone.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static Integer readCoord(ConfigurationSection zone, String corner, String axis) {
        ConfigurationSection section = zone.getConfigurationSection(corner);
        if (section == null) {
            return null;
        }
        String path = axis.toLowerCase(Locale.ROOT);
        if (!section.contains(path)) {
            return null;
        }
        if (!(section.get(path) instanceof Number)) {
            return null;
        }
        return section.getInt(path);
    }

    record ZoneEntry(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
}

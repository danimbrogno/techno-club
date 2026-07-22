package io.github.danimbrogno.regionlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class ZoneRepository {

    private static final int LARGE_AXIS_BLOCKS = 128;

    private final List<LockedZone> zones;
    private final String denyMessage;
    private final String leaveMessage;

    private ZoneRepository(List<LockedZone> zones, String denyMessage, String leaveMessage) {
        this.zones = List.copyOf(zones);
        this.denyMessage = denyMessage == null ? "" : denyMessage;
        this.leaveMessage = leaveMessage == null ? "" : leaveMessage;
    }

    public static ZoneRepository empty() {
        return new ZoneRepository(List.of(), "", "");
    }

    public static ZoneRepository load(FileConfiguration config, Logger logger) {
        String denyMessage = config.getString("deny-message", "This area is locked.");
        String leaveMessage = config.getString("leave-message", "You left the locked area.");
        ConfigurationSection zonesSection = config.getConfigurationSection("zones");
        if (zonesSection == null) {
            return new ZoneRepository(List.of(), denyMessage, leaveMessage);
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

            boolean enabled = zone.getBoolean("enabled", true);
            LockedZone lockedZone = LockedZone.of(name, world, enabled, minX, minY, minZ, maxX, maxY, maxZ);
            loaded.add(lockedZone);
            logger.info("Loaded zone '" + name + "': " + lockedZone.describeBounds()
                    + (enabled ? "" : " [disabled]"));
            if (lockedZone.sizeX() > LARGE_AXIS_BLOCKS
                    || lockedZone.sizeZ() > LARGE_AXIS_BLOCKS) {
                logger.warning("Zone '" + name + "' is very large on X/Z ("
                        + lockedZone.sizeX() + "x" + lockedZone.sizeZ()
                        + "). Enter/leave messages only fire when crossing the boundary — "
                        + "check plugins/RegionLock/config.yml on the server (deploy does not overwrite it).");
            }
        }

        return new ZoneRepository(loaded, denyMessage, leaveMessage);
    }

    /**
     * Test-friendly loader that does not require Bukkit configuration objects.
     */
    static ZoneRepository fromEntries(
            Map<String, ZoneEntry> entries,
            String denyMessage,
            Logger logger
    ) {
        return fromEntries(entries, denyMessage, "", logger);
    }

    static ZoneRepository fromEntries(
            Map<String, ZoneEntry> entries,
            String denyMessage,
            String leaveMessage,
            Logger logger
    ) {
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
                    zone.enabled(),
                    zone.minX(),
                    zone.minY(),
                    zone.minZ(),
                    zone.maxX(),
                    zone.maxY(),
                    zone.maxZ()
            ));
        }
        return new ZoneRepository(loaded, denyMessage, leaveMessage);
    }

    public List<LockedZone> zones() {
        return zones;
    }

    public String denyMessage() {
        return denyMessage;
    }

    public String leaveMessage() {
        return leaveMessage;
    }

    public Optional<LockedZone> findByName(String name) {
        for (LockedZone zone : zones) {
            if (zone.name().equalsIgnoreCase(name)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public boolean isLocked(String world, int x, int y, int z) {
        for (LockedZone zone : zones) {
            if (zone.enabled() && zone.contains(world, x, y, z)) {
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

    record ZoneEntry(
            String world,
            boolean enabled,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        ZoneEntry(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this(world, true, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}

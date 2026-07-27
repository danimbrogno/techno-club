package io.github.danimbrogno.lessonportal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class HubLayout {

    public record LayoutData(
            String hubWorld, Integer hubMinX, Integer hubMinY, Integer hubMinZ,
            Integer hubMaxX, Integer hubMaxY, Integer hubMaxZ,
            String lecternWorld, Integer lecternX, Integer lecternY, Integer lecternZ,
            List<BlockPos> shelves,
            String portalWorld, Integer portalMinX, Integer portalMinY, Integer portalMinZ,
            Integer portalMaxX, Integer portalMaxY, Integer portalMaxZ) {}

    private final Optional<BoundBox> hub;
    private final Optional<BlockPos> lectern;
    private final List<BlockPos> shelves;
    private final Optional<BoundBox> portal;

    private HubLayout(
            Optional<BoundBox> hub,
            Optional<BlockPos> lectern,
            List<BlockPos> shelves,
            Optional<BoundBox> portal) {
        this.hub = hub;
        this.lectern = lectern;
        this.shelves = Collections.unmodifiableList(shelves);
        this.portal = portal;
    }

    public static HubLayout empty() {
        return new HubLayout(Optional.empty(), Optional.empty(), List.of(), Optional.empty());
    }

    public static HubLayout fromData(LayoutData data, Logger logger) {
        Optional<BoundBox> hub = buildBox(
                logger, "hub",
                data.hubWorld(),
                data.hubMinX(), data.hubMinY(), data.hubMinZ(),
                data.hubMaxX(), data.hubMaxY(), data.hubMaxZ());
        Optional<BlockPos> lectern = buildBlockPos(
                logger, "lectern",
                data.lecternWorld(), data.lecternX(), data.lecternY(), data.lecternZ());
        List<BlockPos> shelves = data.shelves() != null ? List.copyOf(data.shelves()) : List.of();
        Optional<BoundBox> portal = buildBox(
                logger, "portal",
                data.portalWorld(),
                data.portalMinX(), data.portalMinY(), data.portalMinZ(),
                data.portalMaxX(), data.portalMaxY(), data.portalMaxZ());
        return new HubLayout(hub, lectern, shelves, portal);
    }

    public static HubLayout load(FileConfiguration config, Logger logger) {
        ConfigurationSection hubSection = config.getConfigurationSection("hub");
        String hubWorld = hubSection != null ? hubSection.getString("world") : null;
        ConfigurationSection hubMin = hubSection != null ? hubSection.getConfigurationSection("min") : null;
        ConfigurationSection hubMax = hubSection != null ? hubSection.getConfigurationSection("max") : null;

        ConfigurationSection lecternSection = config.getConfigurationSection("lectern");
        String lecternWorld = lecternSection != null ? lecternSection.getString("world") : null;
        Integer lecternX = lecternSection != null ? intOrNull(lecternSection, "x") : null;
        Integer lecternY = lecternSection != null ? intOrNull(lecternSection, "y") : null;
        Integer lecternZ = lecternSection != null ? intOrNull(lecternSection, "z") : null;

        List<BlockPos> shelves = new ArrayList<>();
        for (Object entry : config.getMapList("shelves")) {
            String world = stringOrBlank(entry, "world");
            Integer x = intOrNull(entry, "x");
            Integer y = intOrNull(entry, "y");
            Integer z = intOrNull(entry, "z");
            if (world.isBlank() || x == null || y == null || z == null) {
                continue;
            }
            shelves.add(new BlockPos(world, x, y, z));
        }

        ConfigurationSection portalSection = config.getConfigurationSection("portal");
        String portalWorld = portalSection != null ? portalSection.getString("world") : null;
        ConfigurationSection portalMin = portalSection != null ? portalSection.getConfigurationSection("min") : null;
        ConfigurationSection portalMax = portalSection != null ? portalSection.getConfigurationSection("max") : null;

        LayoutData data = new LayoutData(
                hubWorld,
                intOrNull(hubMin, "x"), intOrNull(hubMin, "y"), intOrNull(hubMin, "z"),
                intOrNull(hubMax, "x"), intOrNull(hubMax, "y"), intOrNull(hubMax, "z"),
                lecternWorld, lecternX, lecternY, lecternZ,
                shelves,
                portalWorld,
                intOrNull(portalMin, "x"), intOrNull(portalMin, "y"), intOrNull(portalMin, "z"),
                intOrNull(portalMax, "x"), intOrNull(portalMax, "y"), intOrNull(portalMax, "z"));
        return fromData(data, logger);
    }

    public Optional<BoundBox> hub() {
        return hub;
    }

    public Optional<BlockPos> lectern() {
        return lectern;
    }

    public List<BlockPos> shelves() {
        return shelves;
    }

    public Optional<BoundBox> portal() {
        return portal;
    }

    public boolean isComplete() {
        return hub.isPresent() && lectern.isPresent() && portal.isPresent() && !shelves.isEmpty();
    }

    public HubLayout withHub(BoundBox newHub) {
        return new HubLayout(Optional.of(newHub), lectern, shelves, portal);
    }

    public HubLayout withLectern(BlockPos newLectern) {
        return new HubLayout(hub, Optional.of(newLectern), shelves, portal);
    }

    public HubLayout withShelves(List<BlockPos> newShelves) {
        return new HubLayout(hub, lectern, List.copyOf(newShelves), portal);
    }

    public HubLayout withPortal(BoundBox newPortal) {
        return new HubLayout(hub, lectern, shelves, Optional.of(newPortal));
    }

    private static Optional<BoundBox> buildBox(
            Logger logger, String name,
            String world,
            Integer minX, Integer minY, Integer minZ,
            Integer maxX, Integer maxY, Integer maxZ) {
        if (world == null || world.isBlank()
                || minX == null || minY == null || minZ == null
                || maxX == null || maxY == null || maxZ == null) {
            logger.log(Level.WARNING, "Layout missing or incomplete: {0}", name);
            return Optional.empty();
        }
        return Optional.of(BoundBox.of(world, minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static Optional<BlockPos> buildBlockPos(
            Logger logger, String name,
            String world, Integer x, Integer y, Integer z) {
        if (world == null || world.isBlank() || x == null || y == null || z == null) {
            logger.log(Level.WARNING, "Layout missing or incomplete: {0}", name);
            return Optional.empty();
        }
        return Optional.of(new BlockPos(world, x, y, z));
    }

    private static Integer intOrNull(ConfigurationSection section, String key) {
        if (section == null || !section.contains(key)) {
            return null;
        }
        return section.getInt(key);
    }

    private static Integer intOrNull(Object map, String key) {
        if (!(map instanceof Map<?, ?> entry) || !entry.containsKey(key)) {
            return null;
        }
        Object value = entry.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static String stringOrBlank(Object map, String key) {
        if (!(map instanceof Map<?, ?> entry) || !entry.containsKey(key)) {
            return "";
        }
        Object value = entry.get(key);
        return value != null ? value.toString() : "";
    }
}

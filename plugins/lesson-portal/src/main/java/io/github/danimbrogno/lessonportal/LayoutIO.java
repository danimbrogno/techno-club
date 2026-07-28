package io.github.danimbrogno.lessonportal;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

public final class LayoutIO {

    private LayoutIO() {
    }

    public static HubLayout load(File file, Logger logger) {
        if (!file.exists()) {
            return HubLayout.empty();
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        return HubLayout.load(config, logger);
    }

    public static void save(File file, HubLayout layout) {
        YamlConfiguration config = new YamlConfiguration();

        layout.hub().ifPresent(hub -> {
            config.set("hub.world", hub.world());
            config.set("hub.min.x", hub.minX());
            config.set("hub.min.y", hub.minY());
            config.set("hub.min.z", hub.minZ());
            config.set("hub.max.x", hub.maxX());
            config.set("hub.max.y", hub.maxY());
            config.set("hub.max.z", hub.maxZ());
        });

        layout.lectern().ifPresent(lectern -> {
            config.set("lectern.world", lectern.world());
            config.set("lectern.x", lectern.x());
            config.set("lectern.y", lectern.y());
            config.set("lectern.z", lectern.z());
        });

        List<Map<String, Object>> shelves = new ArrayList<>();
        for (BlockPos shelf : layout.shelves()) {
            shelves.add(Map.of(
                    "world", shelf.world(),
                    "x", shelf.x(),
                    "y", shelf.y(),
                    "z", shelf.z()));
        }
        config.set("shelves", shelves);

        layout.portal().ifPresent(portal -> {
            config.set("portal.world", portal.world());
            config.set("portal.min.x", portal.minX());
            config.set("portal.min.y", portal.minY());
            config.set("portal.min.z", portal.minZ());
            config.set("portal.max.x", portal.maxX());
            config.set("portal.max.y", portal.maxY());
            config.set("portal.max.z", portal.maxZ());
        });

        try {
            config.save(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save layout.yml", e);
        }
    }
}

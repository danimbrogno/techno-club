package io.github.danimbrogno.lessonportal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class LessonCatalog {

    public record LessonEntry(
            String title, String author, String world,
            double x, double y, double z, float yaw, float pitch) {}

    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "portal-inactive", "Place a lesson book on the lectern first.",
            "world-missing", "Lesson world '{world}' is not loaded.",
            "portal-set", "Portal set to {title}.",
            "portal-cleared", "Portal cleared."
    );

    private final Map<String, LessonDefinition> lessons;
    private final Map<String, String> messages;

    private LessonCatalog(Map<String, LessonDefinition> lessons, Map<String, String> messages) {
        this.lessons = Collections.unmodifiableMap(lessons);
        this.messages = Collections.unmodifiableMap(messages);
    }

    public static LessonCatalog fromEntries(
            Map<String, LessonEntry> entries, Map<String, String> messages, Logger logger) {
        Map<String, LessonDefinition> lessons = new LinkedHashMap<>();
        for (Map.Entry<String, LessonEntry> entry : entries.entrySet()) {
            LessonEntry e = entry.getValue();
            if (e.world() == null || e.world().isBlank()) {
                logger.log(Level.WARNING, "Skipping lesson ''{0}'': missing destination world", entry.getKey());
                continue;
            }
            Destination dest = new Destination(e.world(), e.x(), e.y(), e.z(), e.yaw(), e.pitch());
            lessons.put(entry.getKey(), new LessonDefinition(entry.getKey(), e.title(), e.author(), dest));
        }
        Map<String, String> mergedMessages = new LinkedHashMap<>(DEFAULT_MESSAGES);
        mergedMessages.putAll(messages);
        return new LessonCatalog(lessons, mergedMessages);
    }

    public static LessonCatalog load(FileConfiguration config, Logger logger) {
        Map<String, LessonEntry> entries = new LinkedHashMap<>();
        ConfigurationSection lessonsSection = config.getConfigurationSection("lessons");
        if (lessonsSection != null) {
            for (String id : lessonsSection.getKeys(false)) {
                ConfigurationSection lessonSection = lessonsSection.getConfigurationSection(id);
                if (lessonSection == null) {
                    continue;
                }
                String title = lessonSection.getString("title", id);
                String author = lessonSection.getString("author", "");
                ConfigurationSection destSection = lessonSection.getConfigurationSection("destination");
                String world = destSection != null ? destSection.getString("world", "") : "";
                double x = destSection != null ? destSection.getDouble("x", 0) : 0;
                double y = destSection != null ? destSection.getDouble("y", 64) : 64;
                double z = destSection != null ? destSection.getDouble("z", 0) : 0;
                float yaw = destSection != null ? (float) destSection.getDouble("yaw", 0) : 0f;
                float pitch = destSection != null ? (float) destSection.getDouble("pitch", 0) : 0f;
                entries.put(id, new LessonEntry(title, author, world, x, y, z, yaw, pitch));
            }
        }
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection messagesSection = config.getConfigurationSection("messages");
        if (messagesSection != null) {
            for (String key : messagesSection.getKeys(false)) {
                messages.put(key, messagesSection.getString(key, ""));
            }
        }
        return fromEntries(entries, messages, logger);
    }

    public Optional<LessonDefinition> get(String id) {
        return Optional.ofNullable(lessons.get(id));
    }

    public List<LessonDefinition> lessons() {
        return new ArrayList<>(lessons.values());
    }

    public String message(String key) {
        return messages.getOrDefault(key, "");
    }
}

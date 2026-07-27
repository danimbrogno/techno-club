package io.github.danimbrogno.lessonportal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public final class ActiveLessonStore {

    private static final String PREFIX = "active-lesson:";

    private final Path file;
    private Optional<String> current = Optional.empty();

    public ActiveLessonStore(Path file) {
        this.file = file;
    }

    public Optional<String> current() {
        return current;
    }

    public void set(String id) {
        if (id == null || id.isBlank()) {
            clear();
            return;
        }
        current = Optional.of(id);
        save();
    }

    public void clear() {
        current = Optional.empty();
        save();
    }

    public void load() {
        if (!Files.exists(file)) {
            current = Optional.empty();
            return;
        }
        try {
            current = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (current.isEmpty()) {
                Files.writeString(file, "", StandardCharsets.UTF_8);
            } else {
                Files.writeString(file, PREFIX + " " + current.get() + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void retainIfKnown(Set<String> validIds) {
        if (current.isPresent() && !validIds.contains(current.get())) {
            clear();
        }
    }

    private static Optional<String> parse(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(PREFIX)) {
                continue;
            }
            String value = trimmed.substring(PREFIX.length()).trim();
            if (isInactiveValue(value)) {
                return Optional.empty();
            }
            return Optional.of(value);
        }
        return Optional.empty();
    }

    private static boolean isInactiveValue(String value) {
        if (value.isBlank() || "null".equalsIgnoreCase(value)) {
            return true;
        }
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).isBlank();
            }
        }
        return false;
    }
}

package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveLessonStoreTest {

    @Test
    void persistsAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("active-lesson.yml");
        ActiveLessonStore store = new ActiveLessonStore(file);
        store.set("redstone-101");
        ActiveLessonStore reloaded = new ActiveLessonStore(file);
        reloaded.load();
        assertEquals(Optional.of("redstone-101"), reloaded.current());
    }

    @Test
    void retainIfKnownClearsMissing(@TempDir Path dir) {
        Path file = dir.resolve("active-lesson.yml");
        ActiveLessonStore store = new ActiveLessonStore(file);
        store.set("gone");
        store.retainIfKnown(Set.of("redstone-101"));
        assertTrue(store.current().isEmpty());
    }

    @Test
    void parseTreatsQuotedEmptyAndBlankAsInactive(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("active-lesson.yml");
        ActiveLessonStore store = new ActiveLessonStore(file);

        Files.writeString(file, "active-lesson: \"\"\n", StandardCharsets.UTF_8);
        store.load();
        assertTrue(store.current().isEmpty());

        Files.writeString(file, "active-lesson:\n", StandardCharsets.UTF_8);
        store.load();
        assertTrue(store.current().isEmpty());

        Files.writeString(file, "active-lesson:   \n", StandardCharsets.UTF_8);
        store.load();
        assertTrue(store.current().isEmpty());
    }

    @Test
    void loadThrowsOnIoFailure(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("blocked");
        Files.createDirectory(file);
        ActiveLessonStore store = new ActiveLessonStore(file);
        assertThrows(UncheckedIOException.class, store::load);
    }

    @Test
    void saveThrowsOnIoFailure(@TempDir Path dir) throws IOException {
        Path parent = dir.resolve("not-a-dir");
        Files.writeString(parent, "x", StandardCharsets.UTF_8);
        Path file = parent.resolve("active-lesson.yml");
        ActiveLessonStore store = new ActiveLessonStore(file);
        assertThrows(UncheckedIOException.class, () -> store.set("redstone-101"));
    }
}

package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}

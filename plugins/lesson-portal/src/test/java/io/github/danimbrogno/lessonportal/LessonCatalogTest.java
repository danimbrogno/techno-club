package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class LessonCatalogTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void loadsValidLesson() {
        Map<String, LessonCatalog.LessonEntry> entries = new LinkedHashMap<>();
        entries.put("redstone-101", new LessonCatalog.LessonEntry(
                "Redstone 101", "Techno Club", "lesson_redstone", 0, 64, 0, 0f, 0f));
        LessonCatalog catalog = LessonCatalog.fromEntries(entries, Map.of(
                "portal-inactive", "Place a lesson book on the lectern first."
        ), logger);

        assertEquals(1, catalog.lessons().size());
        assertTrue(catalog.get("redstone-101").isPresent());
        assertEquals("lesson_redstone", catalog.get("redstone-101").orElseThrow().destination().world());
        assertEquals("Place a lesson book on the lectern first.", catalog.message("portal-inactive"));
    }

    @Test
    void skipsMissingWorld() {
        Map<String, LessonCatalog.LessonEntry> entries = Map.of(
                "bad", new LessonCatalog.LessonEntry("Bad", "X", "", 0, 64, 0, 0f, 0f)
        );
        LessonCatalog catalog = LessonCatalog.fromEntries(entries, Map.of(), logger);
        assertTrue(catalog.lessons().isEmpty());
    }
}

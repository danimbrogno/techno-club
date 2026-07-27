package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class TeleportGateTest {

    private final Logger logger = Logger.getLogger("test");

    private LessonCatalog catalog() {
        Map<String, LessonCatalog.LessonEntry> entries = new LinkedHashMap<>();
        entries.put("redstone-101", new LessonCatalog.LessonEntry(
                "Redstone 101", "Techno Club", "lesson_redstone", 0, 64, 0, 0f, 0f));
        return LessonCatalog.fromEntries(entries, Map.of(
                "portal-inactive", "Place a lesson book on the lectern first.",
                "world-missing", "Lesson world '{world}' is not loaded."
        ), logger);
    }

    @Test
    void deniesWhenInactive() {
        TeleportDecision decision = TeleportGate.decide(Optional.empty(), catalog(), w -> true);
        TeleportDecision.Deny deny = assertInstanceOf(TeleportDecision.Deny.class, decision);
        assertEquals("Place a lesson book on the lectern first.", deny.message());
    }

    @Test
    void deniesWhenWorldMissing() {
        TeleportDecision decision = TeleportGate.decide(
                Optional.of("redstone-101"), catalog(), w -> false);
        TeleportDecision.Deny deny = assertInstanceOf(TeleportDecision.Deny.class, decision);
        assertEquals("Lesson world 'lesson_redstone' is not loaded.", deny.message());
    }

    @Test
    void allowsWhenReady() {
        TeleportDecision decision = TeleportGate.decide(
                Optional.of("redstone-101"), catalog(), w -> true);
        TeleportDecision.Allow allow = assertInstanceOf(TeleportDecision.Allow.class, decision);
        assertEquals("redstone-101", allow.lesson().id());
    }
}

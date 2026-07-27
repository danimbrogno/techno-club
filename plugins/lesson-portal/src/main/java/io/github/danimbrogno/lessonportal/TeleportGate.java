package io.github.danimbrogno.lessonportal;

import java.util.Optional;
import java.util.function.Predicate;

public final class TeleportGate {

    private TeleportGate() {}

    public static TeleportDecision decide(
            Optional<String> activeId,
            LessonCatalog catalog,
            Predicate<String> worldLoaded) {
        if (activeId.isEmpty()) {
            return new TeleportDecision.Deny(catalog.message("portal-inactive"));
        }
        Optional<LessonDefinition> lesson = catalog.get(activeId.get());
        if (lesson.isEmpty()) {
            return new TeleportDecision.Deny(catalog.message("portal-inactive"));
        }
        String world = lesson.get().destination().world();
        if (!worldLoaded.test(world)) {
            String message = apply(catalog.message("world-missing"), "world", world);
            return new TeleportDecision.Deny(message);
        }
        return new TeleportDecision.Allow(lesson.get());
    }

    static String apply(String template, String key, String value) {
        return template == null ? "" : template.replace("{" + key + "}", value);
    }
}

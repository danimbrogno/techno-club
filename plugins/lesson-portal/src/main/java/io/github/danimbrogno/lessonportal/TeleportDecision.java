package io.github.danimbrogno.lessonportal;

public sealed interface TeleportDecision {
    record Allow(LessonDefinition lesson) implements TeleportDecision {}

    record Deny(String message) implements TeleportDecision {}
}

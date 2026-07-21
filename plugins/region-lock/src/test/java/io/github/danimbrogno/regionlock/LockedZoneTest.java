package io.github.danimbrogno.regionlock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LockedZoneTest {

    private final LockedZone zone = LockedZone.of(
            "spawn-pad",
            "world",
            10,
            80,
            10,
            -10,
            64,
            -10
    );

    @Test
    void containsInteriorPoint() {
        assertTrue(zone.contains("world", 0, 70, 0));
    }

    @Test
    void containsInclusiveCorners() {
        assertTrue(zone.contains("world", -10, 64, -10));
        assertTrue(zone.contains("world", 10, 80, 10));
    }

    @Test
    void rejectsOutsideAndWrongWorld() {
        assertFalse(zone.contains("world", 11, 70, 0));
        assertFalse(zone.contains("world_nether", 0, 70, 0));
    }
}

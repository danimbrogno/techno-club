package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundBoxTest {

    private final BoundBox box = BoundBox.of("world", 10, 80, 10, -10, 64, -10);

    @Test
    void containsInteriorAndCorners() {
        assertTrue(box.contains("world", 0, 70, 0));
        assertTrue(box.contains("world", -10, 64, -10));
        assertTrue(box.contains("world", 10, 80, 10));
    }

    @Test
    void rejectsOutsideAndWrongWorld() {
        assertFalse(box.contains("world", 11, 70, 0));
        assertFalse(box.contains("world_nether", 0, 70, 0));
    }
}

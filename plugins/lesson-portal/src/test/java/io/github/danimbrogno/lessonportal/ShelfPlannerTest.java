package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShelfPlannerTest {

    @Test
    void assignsAcrossShelves() {
        List<BlockPos> shelves = List.of(
                new BlockPos("world", 0, 64, 0),
                new BlockPos("world", 0, 65, 0)
        );
        List<String> ids = List.of("a", "b", "c", "d", "e", "f", "g");
        List<ShelfPlanner.ShelfSlot> plan = ShelfPlanner.plan(shelves, ids);
        assertEquals(7, plan.size());
        assertEquals(0, plan.get(0).slot());
        assertEquals("a", plan.get(0).lessonId());
        assertEquals(0, plan.get(6).slot());
        assertEquals(shelves.get(1), plan.get(6).shelf());
        assertEquals("g", plan.get(6).lessonId());
    }
}

package io.github.danimbrogno.lessonportal;

import java.util.ArrayList;
import java.util.List;

public final class ShelfPlanner {
    public static final int SLOTS_PER_SHELF = 6;

    private ShelfPlanner() {}

    public static List<ShelfSlot> plan(List<BlockPos> shelves, List<String> lessonIds) {
        List<ShelfSlot> out = new ArrayList<>();
        int i = 0;
        for (BlockPos shelf : shelves) {
            for (int slot = 0; slot < SLOTS_PER_SHELF && i < lessonIds.size(); slot++, i++) {
                out.add(new ShelfSlot(shelf, slot, lessonIds.get(i)));
            }
        }
        return List.copyOf(out);
    }

    public record ShelfSlot(BlockPos shelf, int slot, String lessonId) {}
}

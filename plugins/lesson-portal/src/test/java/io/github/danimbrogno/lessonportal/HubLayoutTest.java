package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class HubLayoutTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void loadsCompleteLayout() {
        HubLayout.LayoutData data = new HubLayout.LayoutData(
                "world", -20, 60, -20, 20, 80, 20,
                "world", 10, 64, 5,
                List.of(new BlockPos("world", 8, 64, 5)),
                "world", 14, 64, 4, 14, 66, 6
        );
        HubLayout layout = HubLayout.fromData(data, logger);
        assertTrue(layout.isComplete());
        assertTrue(layout.hub().orElseThrow().contains("world", 0, 70, 0));
        assertEquals(10, layout.lectern().orElseThrow().x());
        assertEquals(1, layout.shelves().size());
        assertTrue(layout.portal().orElseThrow().contains("world", 14, 65, 5));
    }

    @Test
    void incompleteWithoutLectern() {
        HubLayout.LayoutData data = new HubLayout.LayoutData(
                "world", -20, 60, -20, 20, 80, 20,
                null, null, null, null,
                List.of(new BlockPos("world", 8, 64, 5)),
                "world", 14, 64, 4, 14, 66, 6
        );
        assertFalse(HubLayout.fromData(data, logger).isComplete());
    }
}

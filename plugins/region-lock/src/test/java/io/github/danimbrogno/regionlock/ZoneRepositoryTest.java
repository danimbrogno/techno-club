package io.github.danimbrogno.regionlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ZoneRepositoryTest {

    private final Logger logger = Logger.getLogger("ZoneRepositoryTest");

    @Test
    void loadsMultipleZones() {
        Map<String, ZoneRepository.ZoneEntry> entries = new LinkedHashMap<>();
        entries.put("spawn-pad", new ZoneRepository.ZoneEntry("world", -10, 64, -10, 10, 80, 10));
        entries.put("demo", new ZoneRepository.ZoneEntry("world", 100, 60, 100, 120, 70, 120));

        ZoneRepository repo = ZoneRepository.fromEntries(entries, "Locked!", logger);

        assertEquals(2, repo.zones().size());
        assertTrue(repo.isLocked("world", 0, 70, 0));
        assertTrue(repo.isLocked("world", 110, 65, 110));
        assertFalse(repo.isLocked("world", 50, 70, 50));
        assertEquals("Locked!", repo.denyMessage());
    }

    @Test
    void disabledZoneIsNotLocked() {
        Map<String, ZoneRepository.ZoneEntry> entries = Map.of(
                "spawn-pad", new ZoneRepository.ZoneEntry("world", false, -10, 64, -10, 10, 80, 10)
        );

        ZoneRepository repo = ZoneRepository.fromEntries(entries, "Locked!", logger);

        assertFalse(repo.isLocked("world", 0, 70, 0));
        assertTrue(repo.findByName("spawn-pad").isPresent());
        assertFalse(repo.findByName("spawn-pad").get().enabled());
    }

    @Test
    void skipsMissingWorld() {
        Map<String, ZoneRepository.ZoneEntry> entries = Map.of(
                "bad", new ZoneRepository.ZoneEntry("  ", 0, 0, 0, 1, 1, 1)
        );

        ZoneRepository repo = ZoneRepository.fromEntries(entries, "", logger);

        assertTrue(repo.zones().isEmpty());
        assertFalse(repo.isLocked("world", 0, 0, 0));
    }

    @Test
    void emptyEntriesYieldEmptyRepository() {
        ZoneRepository repo = ZoneRepository.fromEntries(Map.of(), "msg", logger);

        assertTrue(repo.zones().isEmpty());
        assertEquals("msg", repo.denyMessage());
    }
}

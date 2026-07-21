# Region Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `plugins/region-lock`, a Paper plugin that cancels block place/break inside named AABB zones from `config.yml`.

**Architecture:** Copy `club-support`, add `LockedZone` + `ZoneRepository` + `BlockLockListener`. Pure unit tests cover zone containment; Bukkit events stay thin.

**Tech Stack:** Paper API 1.21.1, Java 21, Gradle 8.11, JUnit 5 for unit tests

## Global Constraints

- Paper 1.21.x plugin, Java 21 toolchain
- Standalone plugin under `plugins/region-lock`, bootstrapped from `plugins/club-support`
- Group/package: `io.github.danimbrogno.regionlock`
- No bypass permission in v1
- No commands in v1
- Only `BlockBreakEvent` and `BlockPlaceEvent`

---

### Task 1: Scaffold plugin from club-support

**Files:**
- Create: `plugins/region-lock/` (copy of `plugins/club-support` with renames)
- Create: `plugins/region-lock/src/main/resources/config.yml`
- Modify: root `README.md` (add region-lock to starters table)

**Interfaces:**
- Produces: buildable Gradle project `region-lock` with main class `RegionLockPlugin`

- [ ] **Step 1: Copy starter and rename identifiers**

```bash
cp -a plugins/club-support plugins/region-lock
```

Then set:

- `settings.gradle.kts` → `rootProject.name = "region-lock"`
- `build.gradle.kts` → `archiveBaseName.set("RegionLock")`, add JUnit 5 test deps + `tasks.test { useJUnitPlatform() }`
- `plugin.yml` → name `RegionLock`, main `io.github.danimbrogno.regionlock.RegionLockPlugin`
- Move package to `io.github.danimbrogno.regionlock`
- Main class `RegionLockPlugin` with enable/disable log lines
- `README.md` and `.devcontainer/devcontainer.json` titles updated for Region Lock
- Default `config.yml`:

```yaml
deny-message: "This area is locked."

zones: {}
```

- [ ] **Step 2: Verify build**

Run: `cd plugins/region-lock && ./gradlew build`  
Expected: BUILD SUCCESSFUL, JAR at `build/libs/RegionLock-1.0.0-SNAPSHOT.jar`

- [ ] **Step 3: Commit**

```bash
git add plugins/region-lock README.md
git commit -m "feat(region-lock): scaffold plugin from club-support"
```

---

### Task 2: LockedZone + containment tests

**Files:**
- Create: `plugins/region-lock/src/main/java/io/github/danimbrogno/regionlock/LockedZone.java`
- Create: `plugins/region-lock/src/test/java/io/github/danimbrogno/regionlock/LockedZoneTest.java`

**Interfaces:**
- Produces: `LockedZone` record/class with factory that normalizes corners and `boolean contains(String world, int x, int y, int z)`

- [ ] **Step 1: Write failing tests**

```java
package io.github.danimbrogno.regionlock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LockedZoneTest {

    private final LockedZone zone = LockedZone.of(
            "spawn-pad",
            "world",
            10, 80, 10,
            -10, 64, -10
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
```

- [ ] **Step 2: Run tests — expect failure**

Run: `cd plugins/region-lock && ./gradlew test --tests io.github.danimbrogno.regionlock.LockedZoneTest`  
Expected: FAIL (class missing)

- [ ] **Step 3: Implement LockedZone**

```java
package io.github.danimbrogno.regionlock;

public final class LockedZone {
    private final String name;
    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    private LockedZone(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.name = name;
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static LockedZone of(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new LockedZone(
                name,
                world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)
        );
    }

    public String name() { return name; }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

Run: `cd plugins/region-lock && ./gradlew test --tests io.github.danimbrogno.regionlock.LockedZoneTest`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add plugins/region-lock/src
git commit -m "feat(region-lock): add LockedZone with containment checks"
```

---

### Task 3: ZoneRepository config loading

**Files:**
- Create: `plugins/region-lock/src/main/java/io/github/danimbrogno/regionlock/ZoneRepository.java`
- Create: `plugins/region-lock/src/test/java/io/github/danimbrogno/regionlock/ZoneRepositoryTest.java`
- Modify: `plugins/region-lock/src/main/java/io/github/danimbrogno/regionlock/RegionLockPlugin.java`

**Interfaces:**
- Consumes: `LockedZone.of(...)`
- Produces: `ZoneRepository.load(FileConfiguration, Logger) → List<LockedZone>`; `boolean isLocked(String world, int x, int y, int z)`; `String denyMessage()`

- [ ] **Step 1: Write failing tests for YAML parsing via Bukkit MemoryConfiguration OR plain map helpers**

Prefer a package-visible static parser that accepts primitives so tests do not need a server:

```java
// ZoneRepository.fromEntries(Map<String, ZoneEntry>, String denyMessage)
// ZoneEntry record with world, x1..z2
```

Tests:

1. Valid two zones → size 2, containment works
2. Missing world → skipped, empty or partial list
3. Empty zones → empty list

- [ ] **Step 2: Implement repository + wire plugin load/reload**

`RegionLockPlugin` holds a `ZoneRepository`, reloads it in `onEnable` and `reloadConfig` override if present (or custom reload path via `reloadConfig()` then rebuild repository).

- [ ] **Step 3: Run unit tests + `./gradlew build`**

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(region-lock): load named zones from config.yml"
```

---

### Task 4: BlockLockListener

**Files:**
- Create: `plugins/region-lock/src/main/java/io/github/danimbrogno/regionlock/BlockLockListener.java`
- Modify: `plugins/region-lock/src/main/java/io/github/danimbrogno/regionlock/RegionLockPlugin.java`

**Interfaces:**
- Consumes: `ZoneRepository.isLocked`, `denyMessage`
- Produces: listener registered on enable

- [ ] **Step 1: Implement listener**

On `BlockBreakEvent` / `BlockPlaceEvent`: get block location; if `repository.isLocked(world, x, y, z)` → cancel; if deny message non-blank → `player.sendMessage(...)`.

- [ ] **Step 2: Register in `onEnable`**

```java
getServer().getPluginManager().registerEvents(new BlockLockListener(this::repository), this);
```

Use a supplier or mutable holder so reload updates zones without re-registering if reload is added later. For v1, reload via `/reload confirm` recreates the plugin; loading once on enable is enough. Still implement `reloadZones()` called from `onEnable` after `reloadConfig()` for clarity.

- [ ] **Step 3: Full build**

Run: `cd plugins/region-lock && ./gradlew build`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(region-lock): cancel place and break inside locked zones"
```

---

### Task 5: Docs polish

**Files:**
- Modify: `plugins/region-lock/README.md`
- Modify: root `README.md`

- [ ] **Step 1: Document config example and build/output JAR name**
- [ ] **Step 2: Commit**

```bash
git commit -m "docs(region-lock): document config and build"
```

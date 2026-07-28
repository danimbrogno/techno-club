# Lesson Portal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `plugins/lesson-portal`, a Paper plugin that binds a hub lectern/shelves/portal, sets a shared lesson destination from tagged books, and teleports any player who enters the portal when a lesson is active.

**Architecture:** Copy `club-support`, keep pure domain types (`BoundBox`, `LessonCatalog`, `HubLayout`, `ActiveLessonStore`, `TeleportGate`) unit-tested without a server, and keep Bukkit listeners/commands thin. Layout is saved to `layout.yml` via bind commands; catalog lives in `config.yml`; active lesson persists in `active-lesson.yml`.

**Tech Stack:** Paper API 1.21.1, Java 21, Gradle 8.x, JUnit 5

## Global Constraints

- Paper 1.21.x plugin, Java 21 toolchain
- Standalone plugin under `plugins/lesson-portal`, bootstrapped from `plugins/club-support`
- Group/package: `io.github.danimbrogno.lessonportal`
- Shared portal destination; empty lectern = portal inactive
- Hub-only tagged books; no `lessonportal.bypass` in v1
- Worlds are a soft dependency (Bukkit world-by-name at teleport time)
- No Multiverse API dependency
- `/lessonportal reload` must apply config + layout without server reboot

## File structure

| File | Responsibility |
|------|----------------|
| `LessonPortalPlugin.java` | Lifecycle, load/reload, register listeners/commands |
| `BoundBox.java` | Inclusive AABB + `contains` |
| `BlockPos.java` | World + block coords |
| `Destination.java` | World spawn pose |
| `LessonDefinition.java` | Catalog entry |
| `LessonCatalog.java` | Parse/load lessons + messages |
| `HubLayout.java` | Parse/load hub, lectern, shelves, portal |
| `ActiveLessonStore.java` | In-memory + file persist active lesson id |
| `TeleportGate.java` | Pure allow/deny decision for portal enter |
| `ShelfPlanner.java` | Assign lesson ids to shelf slots (6 per chiseled bookshelf) |
| `BookFactory.java` | Create/read PDC-tagged written books |
| `ShelfService.java` | Repair shelf contents from catalog + layout |
| `LecternListener.java` | Lectern place/remove → active lesson |
| `PortalListener.java` | Enter portal volume → teleport or deny |
| `BookGuardListener.java` | Hub leash + destroy/hopper/death rules |
| `SelectionSession.java` | Per-player pos1/pos2 for binds |
| `LayoutIO.java` | Read/write `layout.yml` |
| `LessonPortalCommand.java` | Admin commands |
| `config.yml` / `layout.yml` | Defaults shipped in jar |

---

### Task 1: Scaffold plugin from club-support

**Files:**
- Create: `plugins/lesson-portal/` (copy of `plugins/club-support` with renames)
- Create: `plugins/lesson-portal/src/main/resources/config.yml`
- Create: `plugins/lesson-portal/src/main/resources/layout.yml`
- Modify: root `README.md` (add lesson-portal row)

**Interfaces:**
- Produces: buildable Gradle project with main class `LessonPortalPlugin`

- [ ] **Step 1: Copy starter and rename identifiers**

```bash
cp -a plugins/club-support plugins/lesson-portal
```

Then set:

- `settings.gradle.kts` → `rootProject.name = "lesson-portal"`
- `build.gradle.kts` → `archiveBaseName.set("LessonPortal")`, add JUnit 5 like region-lock:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- `plugin.yml`:

```yaml
name: LessonPortal
version: ${version}
main: io.github.danimbrogno.lessonportal.LessonPortalPlugin
api-version: '1.21'
description: Hub lesson book selector and shared portal teleport.
commands:
  lessonportal:
    description: Bind hub layout, reload, and status for Lesson Portal.
    usage: /lessonportal <pos1|pos2|sethub|setportal|setlectern|addshelf|clearshelves|reload|status|clear>
    permission: lessonportal.admin
permissions:
  lessonportal.admin:
    description: Administer Lesson Portal binds and reload.
    default: op
```

- Move package to `io.github.danimbrogno.lessonportal`
- Main class `LessonPortalPlugin` with enable/disable log lines
- Update plugin `README.md` title for Lesson Portal
- Default `config.yml`:

```yaml
lessons:
  redstone-101:
    title: "Redstone 101"
    author: "Techno Club"
    destination:
      world: lesson_redstone
      x: 0
      y: 64
      z: 0
      yaw: 0
      pitch: 0

messages:
  portal-inactive: "Place a lesson book on the lectern first."
  world-missing: "Lesson world '{world}' is not loaded."
  portal-set: "Portal set to {title}."
  portal-cleared: "Portal cleared."
```

- Default `layout.yml`:

```yaml
# Bound by /lessonportal commands (or edit + /lessonportal reload)
hub: {}
lectern: {}
shelves: []
portal: {}
```

- Root `README.md` starters table: add `| plugins/lesson-portal | Lectern book selector + shared lesson portal |`

- [ ] **Step 2: Verify build**

Run: `cd plugins/lesson-portal && ./gradlew build`  
Expected: BUILD SUCCESSFUL, JAR at `build/libs/LessonPortal-1.0.0-SNAPSHOT.jar`

- [ ] **Step 3: Commit**

```bash
git add plugins/lesson-portal README.md
git commit -m "feat(lesson-portal): scaffold plugin from club-support"
```

---

### Task 2: BoundBox + BlockPos

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BoundBox.java`
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BlockPos.java`
- Create: `plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/BoundBoxTest.java`

**Interfaces:**
- Produces:
  - `record BlockPos(String world, int x, int y, int z)`
  - `BoundBox.of(String world, int x1,y1,z1, int x2,y2,z2)` normalizing corners
  - `boolean BoundBox.contains(String world, int x, int y, int z)`
  - `String BoundBox.describe()`

- [ ] **Step 1: Write failing tests**

```java
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
```

- [ ] **Step 2: Run tests — expect failure**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.BoundBoxTest`  
Expected: FAIL (class missing)

- [ ] **Step 3: Implement**

```java
package io.github.danimbrogno.lessonportal;

public record BlockPos(String world, int x, int y, int z) {}

public final class BoundBox {
    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    private BoundBox(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static BoundBox of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new BoundBox(
                world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)
        );
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public String world() { return world; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public String describe() {
        return world + " (" + minX + "," + minY + "," + minZ + ") -> ("
                + maxX + "," + maxY + "," + maxZ + ")";
    }
}
```

Put `BlockPos` in its own file `BlockPos.java` (same package); keep `BoundBox` in `BoundBox.java`.

- [ ] **Step 4: Run tests — expect pass**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.BoundBoxTest`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BoundBox.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BlockPos.java \
        plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/BoundBoxTest.java
git commit -m "feat(lesson-portal): add BoundBox and BlockPos"
```

---

### Task 3: LessonCatalog

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/Destination.java`
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonDefinition.java`
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonCatalog.java`
- Create: `plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/LessonCatalogTest.java`

**Interfaces:**
- Produces:
  - `record Destination(String world, double x, double y, double z, float yaw, float pitch)`
  - `record LessonDefinition(String id, String title, String author, Destination destination)`
  - `LessonCatalog.fromEntries(Map<String, LessonEntry> entries, Map<String, String> messages, Logger logger)`
  - `LessonCatalog.load(FileConfiguration config, Logger logger)` for runtime
  - `Optional<LessonDefinition> get(String id)`
  - `List<LessonDefinition> lessons()` (stable iteration order = config key order)
  - `String message(String key)` with `{world}` / `{title}` left unsubstituted here (substitution at use site)

```java
record LessonEntry(String title, String author, String world, double x, double y, double z, float yaw, float pitch) {}
```

- [ ] **Step 1: Write failing tests**

```java
package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class LessonCatalogTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void loadsValidLesson() {
        Map<String, LessonCatalog.LessonEntry> entries = new LinkedHashMap<>();
        entries.put("redstone-101", new LessonCatalog.LessonEntry(
                "Redstone 101", "Techno Club", "lesson_redstone", 0, 64, 0, 0f, 0f));
        LessonCatalog catalog = LessonCatalog.fromEntries(entries, Map.of(
                "portal-inactive", "Place a lesson book on the lectern first."
        ), logger);

        assertEquals(1, catalog.lessons().size());
        assertTrue(catalog.get("redstone-101").isPresent());
        assertEquals("lesson_redstone", catalog.get("redstone-101").orElseThrow().destination().world());
        assertEquals("Place a lesson book on the lectern first.", catalog.message("portal-inactive"));
    }

    @Test
    void skipsMissingWorld() {
        Map<String, LessonCatalog.LessonEntry> entries = Map.of(
                "bad", new LessonCatalog.LessonEntry("Bad", "X", "", 0, 64, 0, 0f, 0f)
        );
        LessonCatalog catalog = LessonCatalog.fromEntries(entries, Map.of(), logger);
        assertTrue(catalog.lessons().isEmpty());
    }
}
```

- [ ] **Step 2: Run tests — expect failure**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.LessonCatalogTest`  
Expected: FAIL (class missing)

- [ ] **Step 3: Implement Destination, LessonDefinition, LessonCatalog**

`fromEntries`: skip blank world; log warning.  
`load(FileConfiguration, Logger)`: read `lessons.*` and `messages.*` mirroring default `config.yml` shape (`destination.world/x/y/z/yaw/pitch`). Default messages if keys missing:

- `portal-inactive` → `Place a lesson book on the lectern first.`
- `world-missing` → `Lesson world '{world}' is not loaded.`
- `portal-set` → `Portal set to {title}.`
- `portal-cleared` → `Portal cleared.`

`message(String key)` returns configured string or `""`.

- [ ] **Step 4: Run tests — expect pass**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.LessonCatalogTest`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/Destination.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonDefinition.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonCatalog.java \
        plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/LessonCatalogTest.java
git commit -m "feat(lesson-portal): add LessonCatalog parsing"
```

---

### Task 4: HubLayout

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/HubLayout.java`
- Create: `plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/HubLayoutTest.java`

**Interfaces:**
- Produces:
  - `HubLayout.empty()`
  - `HubLayout.fromData(LayoutData data, Logger logger)` and `load(FileConfiguration, Logger)`
  - `Optional<BoundBox> hub()`, `Optional<BlockPos> lectern()`, `List<BlockPos> shelves()`, `Optional<BoundBox> portal()`
  - `boolean isComplete()` → hub + lectern + portal present and ≥1 shelf (shelves required for repair; document in status)

```java
record LayoutData(
        String hubWorld, Integer hubMinX, Integer hubMinY, Integer hubMinZ,
        Integer hubMaxX, Integer hubMaxY, Integer hubMaxZ,
        String lecternWorld, Integer lecternX, Integer lecternY, Integer lecternZ,
        List<BlockPos> shelves,
        String portalWorld, Integer portalMinX, Integer portalMinY, Integer portalMinZ,
        Integer portalMaxX, Integer portalMaxY, Integer portalMaxZ
) {}
```

Incomplete sections become empty optionals (log warning once per missing piece on load). Normalize box corners via `BoundBox.of`.

- [ ] **Step 1: Write failing tests**

```java
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
```

- [ ] **Step 2: Run tests — expect failure**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.HubLayoutTest`  
Expected: FAIL

- [ ] **Step 3: Implement HubLayout**

`load(FileConfiguration cfg, Logger log)` maps YAML:

- `hub.world` + `hub.min/max.{x,y,z}`
- `lectern.world/x/y/z`
- `shelves` list of `{world,x,y,z}`
- `portal.world` + `portal.min/max.{x,y,z}`

Skip invalid shelf entries (blank world or missing coords).

- [ ] **Step 4: Run tests — expect pass**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.HubLayoutTest`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/HubLayout.java \
        plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/HubLayoutTest.java
git commit -m "feat(lesson-portal): add HubLayout parsing"
```

---

### Task 5: ActiveLessonStore + TeleportGate

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/ActiveLessonStore.java`
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/TeleportGate.java`
- Create: `plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/ActiveLessonStoreTest.java`
- Create: `plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/TeleportGateTest.java`

**Interfaces:**
- Produces:
  - `ActiveLessonStore(Path file)` with `Optional<String> current()`, `void set(String id)`, `void clear()`, `void load()`, `void save()`, `void retainIfKnown(Set<String> validIds)`
  - File format (UTF-8 plain YAML subset):

```yaml
active-lesson: redstone-101
```

    Empty/missing/null/`""` means inactive. Implement with simple line parse/write (no Bukkit YAML required in unit tests).

  - `sealed interface TeleportDecision` with `record Allow(LessonDefinition lesson)` and `record Deny(String message)` (placeholders resolved inside `decide`)
  - `TeleportGate.decide(Optional<String> activeId, LessonCatalog catalog, Predicate<String> worldLoaded) → TeleportDecision`

Rules:

1. No active id → `Deny(catalog.message("portal-inactive"))`
2. Unknown id → `Deny(catalog.message("portal-inactive"))`
3. World not loaded → `Deny` with `{world}` replaced in `world-missing`
4. Else `Allow(lesson)`

- [ ] **Step 1: Write failing tests**

```java
package io.github.danimbrogno.lessonportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveLessonStoreTest {

    @Test
    void persistsAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("active-lesson.yml");
        ActiveLessonStore store = new ActiveLessonStore(file);
        store.set("redstone-101");
        ActiveLessonStore reloaded = new ActiveLessonStore(file);
        reloaded.load();
        assertEquals(Optional.of("redstone-101"), reloaded.current());
    }

    @Test
    void retainIfKnownClearsMissing(@TempDir Path dir) {
        Path file = dir.resolve("active-lesson.yml");
        ActiveLessonStore store = new ActiveLessonStore(file);
        store.set("gone");
        store.retainIfKnown(Set.of("redstone-101"));
        assertTrue(store.current().isEmpty());
    }
}

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
```

Use a top-level sealed interface name `TeleportDecision` (same as produced API above). If the implementation file is `TeleportGate.java`, define:

```java
public sealed interface TeleportDecision {
    record Allow(LessonDefinition lesson) implements TeleportDecision {}
    record Deny(String message) implements TeleportDecision {}
}
```

either in `TeleportDecision.java` or nested/adjacent in the same package.

- [ ] **Step 2: Run tests — expect failure**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.ActiveLessonStoreTest --tests io.github.danimbrogno.lessonportal.TeleportGateTest`  
Expected: FAIL

- [ ] **Step 3: Implement ActiveLessonStore + TeleportGate**

Helper for messages:

```java
static String apply(String template, String key, String value) {
    return template == null ? "" : template.replace("{" + key + "}", value);
}
```

Keep `apply` as package-private on `TeleportGate` or a tiny `Messages` util in the same package.

- [ ] **Step 4: Run tests — expect pass**

Run: `cd plugins/lesson-portal && ./gradlew test`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/ActiveLessonStore.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/TeleportGate.java \
        plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/ActiveLessonStoreTest.java \
        plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/TeleportGateTest.java
git commit -m "feat(lesson-portal): add active lesson store and teleport gate"
```

---

### Task 6: ShelfPlanner

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/ShelfPlanner.java`
- Create: `plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/ShelfPlannerTest.java`

**Interfaces:**
- Produces: `List<ShelfSlot> ShelfPlanner.plan(List<BlockPos> shelves, List<String> lessonIds)`
- `record ShelfSlot(BlockPos shelf, int slot, String lessonId)` where `slot` is `0..5` (chiseled bookshelf capacity)
- Assignment: catalog order, fill shelf0 slots 0..5, then shelf1, etc. Extra lessons beyond capacity are omitted (log at call site). Extra slots left unused.

- [ ] **Step 1: Write failing test**

```java
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
```

- [ ] **Step 2: Run — expect failure**

Run: `cd plugins/lesson-portal && ./gradlew test --tests io.github.danimbrogno.lessonportal.ShelfPlannerTest`  
Expected: FAIL

- [ ] **Step 3: Implement**

```java
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
```

- [ ] **Step 4: Run — expect pass**

- [ ] **Step 5: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/ShelfPlanner.java \
        plugins/lesson-portal/src/test/java/io/github/danimbrogno/lessonportal/ShelfPlannerTest.java
git commit -m "feat(lesson-portal): add shelf slot planner"
```

---

### Task 7: BookFactory + plugin reload wiring

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BookFactory.java`
- Modify: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalPlugin.java`
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LayoutIO.java`

**Interfaces:**
- Produces:
  - `BookFactory(JavaPlugin plugin)` with `NamespacedKey` `lesson_id` (string PDC)
  - `ItemStack create(LessonDefinition lesson)` — `WRITTEN_BOOK`, title/author, one page `"Techno Club lesson: {id}"`, PDC `lesson_id`
  - `Optional<String> readLessonId(ItemStack stack)`
  - `boolean isLessonBook(ItemStack stack)`
  - `LessonPortalPlugin.reloadAll()` loads `config.yml` via `reloadConfig()`, loads `layout.yml` via `LayoutIO`, rebuilds catalog/layout, `activeStore.retainIfKnown(catalog ids)`, runs shelf repair when service exists
  - `LayoutIO.load(File file, Logger)` / `LayoutIO.save(File file, HubLayout layout)` using Bukkit `YamlConfiguration`

No unit test for BookFactory (needs ItemMeta). Compile-only verification via `./gradlew build`.

- [ ] **Step 1: Implement BookFactory**

```java
public final class BookFactory {
    private final NamespacedKey lessonKey;

    public BookFactory(JavaPlugin plugin) {
        this.lessonKey = new NamespacedKey(plugin, "lesson_id");
    }

    public ItemStack create(LessonDefinition lesson) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(lesson.title());
        meta.setAuthor(lesson.author());
        meta.addPages(Component.text("Techno Club lesson: " + lesson.id()));
        meta.getPersistentDataContainer().set(lessonKey, PersistentDataType.STRING, lesson.id());
        book.setItemMeta(meta);
        return book;
    }

    public Optional<String> readLessonId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(lessonKey, PersistentDataType.STRING);
        return Optional.ofNullable(id).filter(s -> !s.isBlank());
    }
}
```

Use Adventure `Component` pages (Paper 1.21).

- [ ] **Step 2: Implement LayoutIO save/load**

Save shape must match spec `layout.yml`. Load delegates to `HubLayout.load`.

- [ ] **Step 3: Wire LessonPortalPlugin fields**

On enable:

1. `saveDefaultConfig()`; save default `layout.yml` resource if missing (`saveResource("layout.yml", false)`)
2. Construct `BookFactory`, `ActiveLessonStore(new File(getDataFolder(), "active-lesson.yml").toPath())` → `load()`
3. `reloadAll()` without shelf repair yet (null-safe)
4. Log active lesson if present

Hold mutable refs: `LessonCatalog catalog`, `HubLayout layout`, `ActiveLessonStore store`, `BookFactory books`.

- [ ] **Step 4: Build**

Run: `cd plugins/lesson-portal && ./gradlew build`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BookFactory.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LayoutIO.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalPlugin.java \
        plugins/lesson-portal/src/main/resources/
git commit -m "feat(lesson-portal): add BookFactory, LayoutIO, and reload wiring"
```

---

### Task 8: LecternListener

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LecternListener.java`
- Modify: `LessonPortalPlugin.java` (register listener)

**Interfaces:**
- Consumes: `HubLayout.lectern()`, `BookFactory`, `LessonCatalog`, `ActiveLessonStore`
- Produces: listener that updates shared active lesson

Behavior:

1. On `PlayerInteractEvent` or inventory/lectern events that place a book into the bound lectern block: if block matches `layout.lectern()`, read lesson id; if unknown → cancel; if known → `store.set(id)` and broadcast `portal-set` with `{title}`.
2. On remove (lectern book extracted / inventory click taking book): `store.clear()` + `portal-cleared` message to player (or broadcast — use broadcast for set, player message for clear is fine; match spec “optional broadcast” — broadcast both for visibility).

Prefer Paper events:

- `PlayerInteractEvent` with lectern + book in hand for placing
- Monitor `InventoryClickEvent` / `InventoryDragEvent` when top inventory holder is `Lectern` at bound position for remove/place

If place cancelled for unknown book, do not change store.

- [ ] **Step 1: Implement LecternListener + register in plugin**

Helper on plugin: `boolean isBoundLectern(Block block)`.

- [ ] **Step 2: Build**

Run: `cd plugins/lesson-portal && ./gradlew build`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LecternListener.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalPlugin.java
git commit -m "feat(lesson-portal): sync active lesson from bound lectern"
```

---

### Task 9: PortalListener

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/PortalListener.java`
- Modify: `LessonPortalPlugin.java`

**Interfaces:**
- Consumes: `HubLayout.portal()`, `ActiveLessonStore`, `LessonCatalog`, `TeleportGate`
- On `PlayerMoveEvent` (block-position change only): if `to` inside portal box and `from` outside (or always while inside with cooldown):

```java
TeleportDecision decision = TeleportGate.decide(
        store.current(),
        catalog,
        worldName -> Bukkit.getWorld(worldName) != null
);
```

- `Deny` → send message (debounce per player ~2s via `Map<UUID, Long>` to avoid spam)
- `Allow` → `player.leaveVehicle()` if needed; `player.teleport(Location)` from `Destination`; if teleport fails, send deny message

- [ ] **Step 1: Implement + register**

- [ ] **Step 2: Build**

Run: `cd plugins/lesson-portal && ./gradlew build`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/PortalListener.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalPlugin.java
git commit -m "feat(lesson-portal): teleport through bound portal volume"
```

---

### Task 10: ShelfService

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/ShelfService.java`
- Modify: `LessonPortalPlugin.java` (call repair on enable/reload)

**Interfaces:**
- Produces: `void repair()` 
- For each `ShelfPlanner.plan(layout.shelves(), catalog.lessons().stream().map(LessonDefinition::id).toList())`:
  - Resolve block; if type != `CHISELED_BOOKSHELF`, log warning and skip
  - `ChiseledBookshelf` block data / inventory: ensure slot has a book whose `readLessonId` matches; if empty or wrong, set `bookFactory.create(definition)`
- Do not delete extra non-lesson books in unused slots in v1 (YAGNI); do replace wrong lesson ids in planned slots

Paper API: `ChiseledBookshelf` implements inventory-like access via `BlockInventoryHolder` / `ChiseledBookshelf.getInventory()` — use the Paper 1.21 inventory API available on the block state.

- [ ] **Step 1: Implement ShelfService.repair + call from reloadAll/enable**

- [ ] **Step 2: Build**

Run: `cd plugins/lesson-portal && ./gradlew build`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/ShelfService.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalPlugin.java
git commit -m "feat(lesson-portal): repair chiseled bookshelf lesson books"
```

---

### Task 11: BookGuardListener

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BookGuardListener.java`
- Modify: `LessonPortalPlugin.java`

**Interfaces:**
- Consumes: `HubLayout.hub()`, `BookFactory`, `ShelfService`

Rules (tagged books only):

| Event | Action |
|-------|--------|
| `PlayerMoveEvent` leaving hub (from inside → outside) | Strip tagged books from inventory + equipment; `shelfService.repair()` |
| `PlayerDropItemEvent` if drop location outside hub OR player outside hub | `event.setCancelled(true)` if still in hub; if outside, remove entity next tick + repair |
| `EntityDamageEvent` / `ItemDespawnEvent` on item entities with lesson books | Cancel when possible; else repair |
| `InventoryMoveItemEvent` from lectern/chiseled bookshelf hopper pull | Cancel if item is lesson book |
| `PlayerDeathEvent` | Record lesson ids in a `Map<UUID, List<String>>` |
| `PlayerRespawnEvent` | Restore missing ids via `bookFactory.create` into inventory; clear record; `repair()` |

If hub optional empty, guard is no-op (log once on reload that book leash disabled).

- [ ] **Step 1: Implement + register**

- [ ] **Step 2: Build**

Run: `cd plugins/lesson-portal && ./gradlew build`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/BookGuardListener.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalPlugin.java
git commit -m "feat(lesson-portal): hub-only guard for lesson books"
```

---

### Task 12: Commands + SelectionSession

**Files:**
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/SelectionSession.java`
- Create: `plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalCommand.java`
- Modify: `LessonPortalPlugin.java` (set executor/tab completer)
- Modify: `LayoutIO.java` if needed for partial updates

**Interfaces:**
- `SelectionSession`: `Map<UUID, BlockPos> pos1/pos2` setters/getters
- Command executor permission `lessonportal.admin`

| Subcommand | Behavior |
|------------|----------|
| `pos1` / `pos2` | Target block (ray) or player block location → store in session |
| `sethub` | Require pos1+pos2 same world → build `BoundBox` → write layout hub → `reloadAll` layout portion / save + apply |
| `setportal` | Same for portal |
| `setlectern` | Target must be `Material.LECTERN` → save lectern pos |
| `addshelf` | Target must be `CHISELED_BOOKSHELF` → append shelf |
| `clearshelves` | Empty shelf list |
| `reload` | `plugin.reloadAll()` |
| `status` | Print active lesson, hub/lectern/shelves/portal present, block type OK checks |
| `clear` | If lectern bound and is lectern: clear lectern inventory book; `store.clear()`; message `portal-cleared` |

After each layout-mutating command: `LayoutIO.save` then `plugin.applyLayoutAndRepair()` (reload layout file + repair shelves; keep catalog unless `reload`).

Tab-complete subcommands listed above.

- [ ] **Step 1: Implement SelectionSession + LessonPortalCommand + register**

- [ ] **Step 2: Build**

Run: `cd plugins/lesson-portal && ./gradlew build`  
Expected: BUILD SUCCESSFUL, all unit tests pass

- [ ] **Step 3: Commit**

```bash
git add plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/SelectionSession.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalCommand.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LayoutIO.java \
        plugins/lesson-portal/src/main/java/io/github/danimbrogno/lessonportal/LessonPortalPlugin.java
git commit -m "feat(lesson-portal): add bind commands, reload, and status"
```

---

### Task 13: Docs + manual test checklist

**Files:**
- Modify: `plugins/lesson-portal/README.md`
- Modify: root `README.md` if build path examples need updating

**Interfaces:**
- Produces: operator docs matching the design spec

- [ ] **Step 1: Write plugin README** covering:

1. Build/copy JAR
2. Put furniture in a `region-lock` zone
3. Bind: `pos1/pos2`, `sethub`, `setportal`, `setlectern`, `addshelf`
4. Create flat world: `/mv create lesson_redstone normal -t flat`
5. Edit `config.yml` lessons; `/lessonportal reload`
6. Manual checklist:

```text
[ ] /lessonportal status shows complete layout
[ ] Shelves fill with tagged books after reload
[ ] Place book on lectern → portal-set message
[ ] Second player enters portal → arrives in lesson world
[ ] Remove book → portal inactive message
[ ] Edit layout.yml coords → reload picks up changes
[ ] Walk out of hub with a book → book removed and shelf repaired
```

- [ ] **Step 2: Commit**

```bash
git add plugins/lesson-portal/README.md README.md
git commit -m "docs(lesson-portal): operator setup and manual checklist"
```

---

## Spec coverage check

| Spec requirement | Task |
|------------------|------|
| Scaffold `plugins/lesson-portal` | 1 |
| `config.yml` + `layout.yml` + reload | 1, 7, 12 |
| Bind commands | 12 |
| PDC books + shelf repair | 6, 7, 10 |
| Lectern → shared active lesson | 5, 8 |
| Empty = portal off; teleport / deny | 5, 9 |
| Hub-only book guard | 11 |
| Persist active lesson | 5 |
| Unit tests catalog/layout/teleport | 2–6 |
| Multiverse out of scope (docs only) | 13 |
| `/clear` ejects book + clears state | 12 |
| `region-lock` complementary | 13 |

## Placeholder / consistency notes

- Package always `io.github.danimbrogno.lessonportal`
- PDC key name: `lesson_id`
- Chiseled bookshelf slots: `ShelfPlanner.SLOTS_PER_SHELF = 6`
- `TeleportGate` + `ActiveLessonStore` are the pure core; listeners must not duplicate deny rules

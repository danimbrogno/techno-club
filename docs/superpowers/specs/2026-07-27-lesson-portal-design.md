# Lesson Portal Plugin — Design

## Goal

Always-on Paper plugin that lets club members pick a lesson by placing a tagged book from a chiseled bookshelf onto a bound lectern. That sets a **shared** portal destination: any player who enters the bound portal volume teleports to that lesson’s world spawn. Empty lectern means the portal is inactive.

## Context

Techno Club uses standalone Paper 1.21.x plugins (Java 21) under `plugins/` for always-on support and `lessons/` for per-lesson modules. `region-lock` already prevents place/break inside named zones; lesson furniture should sit inside such a zone. Worlds are not created by this plugin — Multiverse (or equivalent) loads named worlds; this plugin only teleports.

## Decisions

| Topic | Choice |
|-------|--------|
| Portal audience | Shared — everyone who enters uses the current lectern destination |
| Empty lectern | Portal inactive (message on enter) |
| Book movement | Hub-only: shelf ↔ hand ↔ lectern; leaving the hub returns/repairs the book |
| Destinations | Named separate worlds + spawn coords (most reliable isolation) |
| Shelf UX | Chiseled bookshelves |
| Linking build ↔ plugin | In-game bind commands write `layout.yml`; YAML remains hand-editable |
| Reload | `/lessonportal reload` applies config + layout without server reboot |
| Approach | Config-linked hub controller (plugin owns state; worlds are soft dependency) |

## Scope (v1)

In scope:

- New plugin at `plugins/lesson-portal` (bootstrap from `club-support`)
- `config.yml` (lessons, messages, book display) + `layout.yml` (bound positions)
- Admin bind commands and `/lessonportal reload` / `status`
- PDC-tagged lesson books; chiseled bookshelf repair
- Lectern place/remove → set/clear persisted `activeLesson`
- Portal volume enter → shared teleport or deny
- Hub-only book guard + shelf repair backstop
- Persist `activeLesson` across restarts
- Unit tests for catalog/layout parsing and pure “may teleport?” logic where practical

Out of scope for v1:

- Creating or loading worlds (use Multiverse or manual world folders)
- Per-player destinations or multiple lectern/portal stations
- Return portal from lesson worlds
- Lesson setup/run/reset lifecycle (belongs in lesson plugins)
- Expanding `region-lock` coverage (explosions, pistons, etc.)
- Fancy portal VFX beyond the in-world frame the builder places
- Optional `lessonportal.bypass` for ops

## Architecture

| Component | Responsibility |
|-----------|----------------|
| `LessonPortalPlugin` | Lifecycle; load config/layout; persist active lesson; register listeners/commands |
| `LessonCatalog` | `lesson_id` → book title/author/pages + destination (world, x/y/z, yaw/pitch) |
| `HubLayout` | Hub zone, lectern block, chiseled-shelf blocks, portal volume from `layout.yml` |
| `BookFactory` | Build/validate items tagged with PDC key `lesson_id` (plugin namespaced) |
| `ShelfService` | Ensure bound shelves hold one tagged book per configured lesson; repair missing |
| `LecternService` | On book place/remove at bound lectern: set or clear `activeLesson` |
| `PortalListener` | Player enters portal volume → teleport if active and world available; else deny |
| `BookGuardListener` | Enforce hub-only rules for tagged books; cancel export/destroy where possible |
| `ActiveLessonStore` | In-memory + `active-lesson.yml` for restart persistence |
| `LessonPortalCommand` | Bind, reload, status (permission `lessonportal.admin`) |

**State machine (shared):**

- `empty` → portal inactive
- `lesson_id` present on lectern → all portal entries go to that lesson’s destination
- Book removed from lectern → back to `empty`

**Dependencies:**

- `region-lock` — protect hub blocks (place/break); not a hard plugin dependency
- Worlds — soft: resolve via Bukkit `World` by name at teleport time (Multiverse may have loaded them)

## Config

### `layout.yml` (bind commands + hand edit)

```yaml
hub:
  world: world
  min: { x: -20, y: 60, z: -20 }
  max: { x: 20, y: 80, z: 20 }

lectern: { world: world, x: 10, y: 64, z: 5 }

shelves:
  - { world: world, x: 8, y: 64, z: 5 }
  - { world: world, x: 8, y: 65, z: 5 }

portal:
  world: world
  min: { x: 14, y: 64, z: 4 }
  max: { x: 14, y: 66, z: 6 }
```

### `config.yml` (lesson catalog)

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

### `active-lesson.yml` (runtime)

```yaml
active-lesson: redstone-101   # or empty/null when inactive
```

Load rules:

- Missing/invalid layout pieces → plugin stays enabled; affected features no-op; `/status` reports gaps
- Missing lesson `destination.world` → skip lesson, log warning
- Empty `lessons` map is valid (nothing to shelve; portal never activates usefully)
- Corner order for boxes does not matter; normalize to min/max on load

## Commands

Permission: `lessonportal.admin` (default op).

| Command | Effect |
|---------|--------|
| `/lessonportal pos1` / `pos2` | Store corners for next box bind (look/stand target) |
| `/lessonportal sethub` | Write hub zone from pos1/pos2 |
| `/lessonportal setportal` | Write portal volume from pos1/pos2 |
| `/lessonportal setlectern` | Write targeted/standing lectern block (must be lectern) |
| `/lessonportal addshelf` | Append targeted chiseled bookshelf |
| `/lessonportal clearshelves` | Clear shelf list in layout |
| `/lessonportal reload` | Reload `config.yml` + `layout.yml`; re-validate `activeLesson` |
| `/lessonportal status` | Show active lesson + whether layout pieces resolve in the world |
| `/lessonportal clear` | Eject book from bound lectern (if any) and clear `activeLesson` so state stays in sync |

Bind commands validate block type, write `layout.yml`, and apply immediately (same apply path as reload).

**Reload:** Re-reads YAML, rebuilds catalog + layout, clears `activeLesson` if that id was removed from config. No server restart. Hand-editing YAML then `/lessonportal reload` is a supported workflow.

## Behavior

### Lectern

1. Player places a book on the bound lectern.
2. Valid catalog `lesson_id` on the item → set `activeLesson`, persist, optional broadcast.
3. Untagged or unknown id → cancel the place; do not change `activeLesson`.
4. Book removed → clear `activeLesson`, persist; portal inactive.

### Portal enter

1. Detect player entering the bound portal volume.
2. No `activeLesson` → deny + `portal-inactive` message.
3. Destination world missing/unloaded → deny + `world-missing`.
4. Else teleport to catalog destination (shared for all players).

### Book guard (tagged lesson books only)

| Situation | Behavior |
|-----------|----------|
| Leave hub zone | Remove tagged books from inventory/hand; shelf repair recreates them on a bound shelf |
| Drop outside hub | Delete the dropped item entity; shelf repair recreates on a bound shelf |
| Destroy / despawn / burn | Cancel damage/despawn when the event allows; otherwise shelf repair recreates |
| Hopper / minecart export from lectern or shelves | Cancel the move |
| Death in hub | On respawn, restore any missing tagged lesson books that were in inventory (shelf repair covers the rest) |

### Shelf repair

On enable, reload, and after guard actions as needed: ensure each configured lesson has a PDC-tagged book on a bound chiseled bookshelf. Recreate from `BookFactory` when missing. v1 mapping: catalog order across bound shelf slots (one book per lesson); per-slot layout overrides can wait.

## Failure handling

| Case | Behavior |
|------|----------|
| Layout incomplete | Enable plugin; selection/teleport no-op; `/status` lists missing pieces |
| Bound block wrong type | Warning; skip that feature until re-bound |
| Unknown book on lectern | Cancel place; leave prior `activeLesson` unchanged |
| `activeLesson` removed from catalog on reload | Clear active; portal off |
| World not loaded | Deny teleport; message includes world name |
| Vehicle / passenger | Teleport the player only; if the player cannot be teleported safely, deny with message |
| Duplicate books for same lesson | Allowed; repair only ensures at least one per lesson |

## Permissions

| Permission | Default | Use |
|------------|---------|-----|
| `lessonportal.admin` | op | Bind, reload, status, clear |
| (none) | everyone | Place/remove lesson books, use portal |

No hub-leash bypass in v1 (including ops) so staff testing matches club rules.

## Linking in-game builds

Builders place lectern, chiseled bookshelves, and portal frame in the hub. An admin binds those blocks via commands (positions saved to `layout.yml`). Cosmetic rebuilds around furniture are free; replacing a bound block requires re-binding that piece. Coordinates may also be edited in YAML and applied with `/lessonportal reload`.

Books are linked by PDC `lesson_id`, not by position. Positions only identify which lectern, shelves, portal volume, and hub leash zone are authoritative.

## World setup (ops, outside this plugin)

Example with Multiverse-Core for a flat empty lesson world:

```text
/mv create lesson_redstone normal -t flat
/mv tp lesson_redstone
/mv setspawn
```

Then set `destination.world: lesson_redstone` (and spawn coords) in `config.yml` for the matching lesson.

## Testing

- Unit-test layout/catalog parse (valid, missing world, normalized corners)
- Unit-test active-lesson transitions (set / clear / reload invalidates)
- Manual on Paper: bind room → shelves fill → lectern sets portal → second player teleports → remove book → portal inactive → YAML edit + reload → walk book out of hub → book restored

## Future (not v1)

- `lessonportal.bypass` for staff
- Return portals / lobby clock
- Multiple stations (per-group lectern + portal)
- Per-shelf slot → `lesson_id` in layout
- Hard integration with Multiverse API (auto-load world on activate)
- Richer book pages generated from lesson plugin metadata

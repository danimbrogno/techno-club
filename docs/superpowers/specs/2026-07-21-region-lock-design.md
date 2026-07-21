# Region Lock Plugin — Design

## Goal

Always-on Paper plugin that prevents players from placing or breaking blocks inside named coordinate ranges defined in `config.yml`. Outside those ranges, vanilla place/break rules apply.

## Context

Techno Club uses standalone Paper 1.21.x plugins (Java 21) under `plugins/` for always-on support. New plugins are bootstrapped by copying `plugins/club-support` and renaming the Gradle project, `plugin.yml`, package, and main class.

## Scope (v1)

In scope:

- New plugin at `plugins/region-lock`
- Multiple named axis-aligned zones in `config.yml`
- Cancel `BlockBreakEvent` and `BlockPlaceEvent` when the affected block is inside any loaded zone
- Lock applies to every player (no bypass permission)
- Configurable denial message (empty = silent)

Out of scope for v1:

- In-game commands to create, edit, or toggle zones
- Bypass permissions
- Explosions, pistons, fire, buckets, entity damage to blocks, and other non-place/break protection

## Architecture

Copy `plugins/club-support` → `plugins/region-lock` and rename identifiers.

| Component | Responsibility |
|-----------|----------------|
| `RegionLockPlugin` | Lifecycle: save default config, load zones, register listener, reload on plugin reload |
| `LockedZone` | Named box: world name + inclusive min/max corners; `contains(world, x, y, z)` |
| `ZoneRepository` | Parse `config.yml` into an immutable list of `LockedZone`; skip invalid entries with warnings |
| `BlockLockListener` | On break/place, cancel if location is inside any zone; optionally notify the player |

## Config

```yaml
deny-message: "This area is locked."

zones:
  spawn-pad:
    world: world
    min: { x: -10, y: 64, z: -10 }
    max: { x: 10, y: 80, z: 10 }
  redstone-demo:
    world: world
    min: { x: 100, y: 60, z: 100 }
    max: { x: 120, y: 70, z: 120 }
```

Load rules:

- Corner order does not matter; normalize to min/max on load
- Bounds are inclusive on all axes
- Missing or blank world name → skip zone, log warning
- Non-numeric or missing coordinates → skip zone, log warning
- Empty `zones` map is valid (plugin enabled, nothing locked)
- `deny-message` empty or omitted → silent cancel

## Behavior

1. On enable: `saveDefaultConfig()`, load zones, register `BlockLockListener`
2. On break or place: if block world+coords match any zone → cancel event; if `deny-message` non-empty, send it to the player
3. Outside all zones → do not cancel
4. No bypass for ops or anyone else

## Testing

- Unit-test `LockedZone.contains` (inside, outside, boundary, world mismatch, normalized corners) without a running server
- Manual check on a Paper server: place/break blocked inside a configured zone, allowed outside

## Future (not v1)

- Commands to toggle zones and create runtime zones saved separately from config
- Broader protection (explosions, pistons, etc.)
- Optional bypass permission

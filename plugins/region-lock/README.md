# Region Lock

Always-on Techno Club support plugin. Prevents players from placing or breaking blocks inside named coordinate zones defined in `config.yml`.

## Build

```bash
./gradlew build
```

Output: `build/libs/RegionLock-1.0.0-SNAPSHOT.jar`

Copy the JAR into your Paper server's `plugins/` folder.

## Config

Edit `plugins/RegionLock/config.yml` on the server (generated on first run):

```yaml
deny-message: "This area is locked."

zones:
  spawn-pad:
    world: world
    min: { x: -10, y: 64, z: -10 }
    max: { x: 10, y: 80, z: 10 }
```

- Corner order does not matter; bounds are inclusive.
- Empty `zones: {}` means nothing is locked.
- Set `deny-message: ""` for a silent cancel.
- Restart the plugin or server after editing config (or use a plugin reload that calls `reloadConfig`).

## Behavior (v1)

- Cancels `BlockBreakEvent` and `BlockPlaceEvent` inside any loaded zone
- Applies to every player (no bypass)
- Does not cover explosions, pistons, fire, or other block damage

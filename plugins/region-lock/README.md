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
enabled: true
deny-message: "This area is locked."

zones:
  spawn-pad:
    enabled: true
    world: world
    min: { x: -10, y: 64, z: -10 }
    max: { x: 10, y: 80, z: 10 }
```

- Corner order does not matter; bounds are inclusive.
- Empty `zones: {}` means nothing is locked.
- Set `deny-message: ""` for a silent cancel.
- `enabled: false` (global or per-zone) disables enforcement; persisted by commands.

## Commands (ops / `regionlock.toggle`)

| Command | Effect |
|---------|--------|
| `/regionlock on` | Global protection on |
| `/regionlock off` | Global protection off |
| `/regionlock toggle` | Flip global on/off |
| `/regionlock on\|off\|toggle <zone>` | Per-zone switch |
| `/regionlock status` | Show global + zone state |
| `/regionlock reload` | Reload `config.yml` |

Alias: `/rlock`

## Behavior

- Cancels place/break (with restore fallback) inside any **enabled** zone while global is on
- Applies to every player (no per-player bypass)
- Does not cover explosions, pistons, fire, or other block damage

# Techno Club

In-game Minecraft lessons for teaching kids video game design.

## Layout

- `plugins/` — always-on Paper plugins that support club sessions
- `lessons/` — individual lesson plugins that can be set up, run, and reset

Each subfolder is a standalone Paper 1.21.x plugin (Java 21), bootstrapped from our plugin template.

## Build a plugin or lesson

```bash
cd plugins/club-support   # or lessons/starter-lesson
./gradlew build
```

JAR output: `build/libs/<Name>-1.0.0-SNAPSHOT.jar` — copy into your Paper server's `plugins/` folder.

## Starters

| Path | Role |
|------|------|
| `plugins/club-support` | Blank always-on support plugin |
| `lessons/starter-lesson` | Blank lesson plugin to copy for new lessons |

To add a new lesson or support plugin, copy the matching starter (or `plugins/template` from the minecraft repo) and rename the Gradle project, `plugin.yml`, package, and main class.

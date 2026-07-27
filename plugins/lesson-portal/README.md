# Lesson Portal

Hub lesson book selector and shared portal teleport for Techno Club sessions. Players pick a lesson book from chiseled bookshelves, place it on a bound lectern to activate the portal, and walk through the portal volume to teleport to the lesson world.

## Build

```bash
cd plugins/lesson-portal
./gradlew build
```

Output: `build/libs/LessonPortal-1.0.0-SNAPSHOT.jar`

Copy the JAR into your Paper server's `plugins/` folder. On first run the plugin creates `plugins/LessonPortal/config.yml`, `layout.yml`, and `active-lesson.yml`.

## Hub setup

Build the hub furniture (lectern, chiseled bookshelves, portal frame) inside a **region-lock** zone so players cannot place or break blocks there. Region Lock keeps the hub intact; Lesson Portal handles books and teleport.

1. Create or extend a region-lock zone that covers the hub area (see `plugins/region-lock/README.md`).
2. Place a lectern, one or more chiseled bookshelves, and mark the portal walk-through volume.
3. Bind the layout with `/lessonportal` (ops / `lessonportal.admin`).

## Bind commands

Use two corner positions for box binds (`sethub`, `setportal`). Stand at each corner and run `pos1` / `pos2`, then apply the bind.

| Command | Effect |
|---------|--------|
| `/lessonportal pos1` | Store targeted block (or feet block) as corner 1 |
| `/lessonportal pos2` | Store targeted block (or feet block) as corner 2 |
| `/lessonportal sethub` | Save hub volume from pos1 + pos2 (same world) |
| `/lessonportal setportal` | Save portal teleport volume from pos1 + pos2 |
| `/lessonportal setlectern` | Save targeted lectern block |
| `/lessonportal addshelf` | Append targeted chiseled bookshelf |
| `/lessonportal clearshelves` | Remove all bound shelves |
| `/lessonportal reload` | Reload `config.yml` + `layout.yml` and repair shelves |
| `/lessonportal status` | Show active lesson and layout completeness |
| `/lessonportal clear` | Remove lectern book and clear active lesson |

Layout binds are written to `plugins/LessonPortal/layout.yml`. After bind commands, shelves are repaired automatically. Edit `layout.yml` by hand if needed, then run `/lessonportal reload`.

## Lesson worlds (Multiverse)

Lesson Portal does not depend on Multiverse; it teleports by world name when the world is loaded. Create flat lesson worlds with Multiverse Core, for example:

```
/mv create lesson_redstone normal -t flat
```

Add matching entries under `lessons:` in `config.yml` (world name, spawn coordinates, yaw/pitch). Reload after edits:

```
/lessonportal reload
```

Example lesson block (also shipped as default):

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
```

Each chiseled bookshelf holds up to six lesson books; extra catalog entries are skipped. Tagged books cannot leave the hub (they are stripped on exit and shelves are repaired).

## Config reload

- `/lessonportal reload` — reloads `config.yml` and `layout.yml`, drops active lesson if its id is no longer in the catalog, and repairs shelf books.
- Server deploy does not overwrite live `plugins/LessonPortal/config.yml` or `layout.yml`; edit those on the server, then reload.

## Manual test checklist

```text
[ ] /lessonportal status shows complete layout
[ ] Shelves fill with tagged books after reload
[ ] Place book on lectern → portal-set message
[ ] Second player enters portal → arrives in lesson world
[ ] Remove book → portal inactive message
[ ] Edit layout.yml coords → reload picks up changes
[ ] Walk out of hub with a book → book removed and shelf repaired
```

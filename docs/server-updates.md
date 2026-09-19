# Server updates (Paper + Geyser)

`scripts/update-server.sh` keeps the Paper server jar and third-party plugins
current on `aztec-validator`. CI deploys it to
`/srv/minecraft/bin/update-server.sh` on merge to `main`, so edit it here and
never on the server.

Plugins built from this repo (`plugins/*`, `lessons/*`) are **not** touched —
those are deployed by `.github/workflows/deploy.yml`.

## What it does

1. Resolves the newest Paper version whose latest build is on the **STABLE**
   channel (via the PaperMC Fill v3 API) and that the installed JDK can run.
   Release candidates, pre-releases and ALPHA builds are skipped.
2. Resolves the latest Geyser and Floodgate builds from the GeyserMC API.
3. Compares SHA-256 against what is on disk. **If nothing changed, nothing is
   downloaded, swapped or restarted** — the cron run is a no-op.
4. Downloads to a staging dir and verifies checksums *before* stopping anything.
5. Skips the run if players are online (unless `--force`).
6. Warns in chat, `save-all`, `stop`, waits for the process to exit, swaps jars,
   restarts the `minecraft` screen session, and waits for `Done (` in the log.
7. Moves replaced jars to `/srv/minecraft/backups/<timestamp>/`, keeping the
   last 5 sets.

### Minecraft version bumps are held by default

A new *build* of the current Minecraft version applies automatically. A new
*Minecraft version* (e.g. 26.1.2 → 26.2) is reported and held, because Geyser,
Floodgate and the club's own plugins need to support it first:

```
HOLD: this is a Minecraft version change (26.1.2 -> 26.2), not just a build update.
```

To take it, check Bedrock support, then run once by hand:

```bash
/srv/minecraft/bin/update-server.sh --allow-version-bump
```

Paper 26.x requires **Java 25+**. The script refuses to install a version the
installed JDK cannot run rather than leaving a server that will not boot.

## One-time server setup

CI writes as `tcdeploy`, which cannot write to `/srv/minecraft` itself, so the
script lands in a dedicated directory:

```bash
sudo apt install -y jq unzip
sudo install -d -o danimbrogno -g tcdeploy -m 775 /srv/minecraft/bin
```

After that, merging to `main` ships the current version automatically — no
restart involved, since cron re-reads the file on every run. Changing only
`scripts/update-server.sh` deploys the script and nothing else: no plugin
rebuild, no server restart.

Verify with:

```bash
/srv/minecraft/bin/update-server.sh --dry-run
```

## Cron

As `danimbrogno` (the user that owns the server process), `crontab -e`:

```cron
# Techno Club: update Paper + Geyser weekly, Monday 05:30
30 5 * * 1 /bin/bash -lc '/srv/minecraft/bin/update-server.sh' >> /srv/minecraft/logs/update.log 2>&1 || echo "Techno Club server update FAILED - see /srv/minecraft/logs/update.log"
```

`bash -lc` gives cron the login `PATH` so `java` resolves; otherwise set
`JAVA_BIN=/path/to/java` in the crontab. Output goes to the log file, and the
trailing `echo` only fires on failure, so cron mails you only when something
broke.

## Options

| Flag | Effect |
|------|--------|
| `--dry-run` | Report what would change; download and modify nothing |
| `--allow-version-bump` | Permit a Minecraft version change |
| `--paper-version 26.2` | Pin a specific Paper version instead of "newest stable" |
| `--only Geyser` | Limit to one manifest entry (`Paper`, `Geyser`, `Floodgate`, …) |
| `--force` | Update even with players online |
| `--no-restart` | Swap jars but leave the server running the old ones |

## Housekeeping this does not do

Paper loads every `*.jar` in `plugins/`, so the dated spare copies currently
sitting there — `Geyser-Spigot.2026-09-01.jar` and
`Floodgate-Spigot.2026-09-01.jar` — are live duplicates of Geyser and
Floodgate. The manifest globs retire them into `backups/` on the first run.

The `.jar.old` and `.jar.bak` copies are inert (Paper ignores them) and are left
untouched; delete them by hand whenever you like.

## Adding another plugin

Edit the `PLUGINS` manifest near the top of the script:

```
"Name|kind|spec|filename-in-plugins|glob-of-jars-this-entry-owns"
```

- `kind=geyser` — `<project>:<platform>`, e.g. `geyser:spigot`
- `kind=modrinth` — `<slug>[:<loader>]`, loader defaults to `paper`
- `kind=url` — a direct download URL

The glob matters: it is how the script retires an old, differently-versioned
copy (`Geyser-Spigot-2.10.0.jar`) instead of leaving two jars for Paper to load.
Anything not in the manifest is left alone.

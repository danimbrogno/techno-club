# Moving the server to a new machine

`scripts/migrate-server.sh` moves the whole server — worlds, configs, plugins,
`bin/` scripts and the update cron entry — from the current machine to a new
one over SSH. CI deploys it to `/srv/minecraft/bin/migrate-server.sh`
alongside the update script, so it is already on the old server.

Run everything below **on the old server, as `danimbrogno`** (the user that
owns the server process).

## 1. Prepare the new machine (once, as root)

`scripts/setup-new-server.sh` recreates everything the old server has outside
`/srv/minecraft`: packages (Java 25+, screen, rsync, jq, …), the
`danimbrogno` and `tcdeploy` users, `/srv/minecraft` with `plugins/` and
`bin/` as `danimbrogno:tcdeploy 775`, the restart helper, tcdeploy's sudoers
rule (validated with `visudo`), and ufw ports if ufw is active. It is safe to
re-run.

First collect two public keys from the **old** server:

```bash
sudo cat ~tcdeploy/.ssh/authorized_keys   # CI's key
cat ~/.ssh/id_ed25519.pub                 # danimbrogno's key (ssh-keygen -t ed25519 if missing)
```

Then on the **new** machine:

```bash
curl -fsSLO https://raw.githubusercontent.com/danimbrogno/techno-club/main/scripts/setup-new-server.sh
curl -fsSLO https://raw.githubusercontent.com/danimbrogno/techno-club/main/scripts/remote-restart.sh
chmod +x setup-new-server.sh
sudo ./setup-new-server.sh --deploy-key "<CI key>" --migrate-key "<danimbrogno key>"
```

If the distro has no `openjdk-25-jre-headless` package (e.g. Debian 12,
Ubuntu 24.04), install Java 25 from [Adoptium](https://adoptium.net/) and
re-run with `--skip-java`.

Still by hand: install Tailscale and join the tailnet (ACLs must let
`tag:ci-technoclub` SSH in), and open TCP 25565 / UDP 19132 in any cloud
firewall.

## 2. Check

```bash
/srv/minecraft/bin/migrate-server.sh check danimbrogno@new-box
```

Changes nothing. Reports missing tools, a Java older than the one running
here, disk space, too little RAM for the `-Xmx` in `startup.sh` (4 GB heap
today, so the new machine needs ~5 GB+), a server already running there, and anything CI will need
after cutover (`tcdeploy`, the restart helper, Tailscale).

## 3. Sync while the server keeps running

```bash
/srv/minecraft/bin/migrate-server.sh sync danimbrogno@new-box --test-boot
```

Pauses autosave (`save-off` / `save-all flush`), rsyncs `/srv/minecraft` to
the new machine, and turns autosave back on. Players can keep playing.
`--test-boot` then starts the copy on the new machine, waits for `Done (`,
and stops it again. Re-run `sync` as often as you like; each run only sends
what changed.

## 4. Cut over (a few minutes of downtime)

```bash
/srv/minecraft/bin/migrate-server.sh cutover danimbrogno@new-box
```

1. Refuses if players are online (`--force` overrides) or if the new machine
   is missing anything CI needs.
2. Warns in chat, saves and stops the server here.
3. Final rsync. If it fails, the server here is restarted and nothing moves.
4. Starts the server on the new machine in the `minecraft` screen session
   and waits for `Done (`.
5. Moves the `update-server.sh` cron entry: installs it on the new machine
   and comments it out here (`#MIGRATED`).
6. Renames `startup.sh` here to `startup.sh.migrated` and writes
   `MIGRATED-TO`, so a CI deploy still pointed at this machine cannot bring
   the old copy back up, and the script refuses to sync from it again.

Then, by hand:

- GitHub → Settings → Environments → `dev`: set `SSH_HOST` to the new host
  and `SSH_KNOWN_HOSTS` to `ssh-keyscan -H <new-host>`, then run the Deploy
  workflow with `deploy_all` to prove CI works end to end.
- Point DNS / tell players the new address.

## Safety rails

- The new machine gets a `.migrated-from` marker. Syncing into a non-empty
  `/srv/minecraft` without one needs `--overwrite`; once the marker says
  `live`, nothing will sync over it.
- The script holds the same lock as `update-server.sh`, so a cron update
  cannot run mid-migration.

## Rolling back

On the new machine, stop the server (`screen -S minecraft -X stuff 'stop\r'`)
and comment out its cron entry. Here:

```bash
cd /srv/minecraft
mv startup.sh.migrated startup.sh && rm MIGRATED-TO
crontab -e    # remove the "#MIGRATED " prefix
screen -dmS minecraft bash -lc "cd /srv/minecraft && exec ./startup.sh"
```

and put the old `SSH_HOST` / `SSH_KNOWN_HOSTS` back in GitHub. Anything played
on the new machine since cutover is lost unless you rsync it back first.

## Options

| Option | Effect |
|--------|--------|
| `-i <keyfile>` | SSH key for the new machine |
| `--test-boot` | (`sync`) boot and stop the copy on the new machine |
| `--force` | (`cutover`) proceed with players online |
| `--overwrite` | allow syncing into a non-empty dir without a marker |
| `REMOTE_DIR=…` | install somewhere other than `/srv/minecraft` on the new machine |
| `WARN_SECONDS=…` | in-game warning before the cutover stop (default 60) |

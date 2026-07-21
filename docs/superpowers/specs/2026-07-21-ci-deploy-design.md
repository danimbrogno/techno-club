# Techno Club CI Deploy Design

**Date:** 2026-07-21  
**Status:** Draft for review  
**Host:** `aztec-validator` (Tailscale tag `tag:technoclub`)  
**Server path:** `/srv/minecraft` (Paper; plugins dir `/srv/minecraft/plugins`)

## Goal

On merge to `main` (and on manual dispatch), GitHub Actions builds changed Paper plugin JARs from this monorepo, copies them into `/srv/minecraft/plugins` over Tailscale SSH, and gracefully restarts the Minecraft process via GNU screen.

## Non-goals (this pass)

- Dockerizing the Minecraft server
- Hot-reload without restart
- Deploying third-party JARs not built from this repo
- Migrating zeenome off `tag:ci` (leave as-is; isolate via a separate CI tag)

## Architecture

```
push/merge to main
        │
        ▼
┌───────────────────┐
│ GitHub Actions    │
│ (ubuntu-latest)   │
│ 1. detect changes │
│ 2. build JARs     │
│ 3. Tailscale join │
│ 4. rsync JARs     │
│ 5. SSH restart    │
└─────────┬─────────┘
          │ tag:ci-technoclub → tag:technoclub:22
          ▼
┌───────────────────┐
│ aztec-validator   │
│ tcdeploy@…        │
│ /srv/minecraft/   │
│   plugins/*.jar   │
│ screen -S minecraft│
│   (user danimbrogno)│
└───────────────────┘
```

## Repo layout (deploy-relevant)

```
plugins/<name>/     # always-on support plugins (Gradle / Paper)
lessons/<name>/     # lesson plugins (same build + deploy path)
.github/workflows/
  deploy.yml
scripts/
  remote-restart.sh   # canonical copy of server restart script (reference)
```

CI treats any subdirectory under `plugins/` or `lessons/` that contains a Gradle build file as a deployable unit. Built JARs land in that unit’s `build/libs/*.jar` (exclude `*-sources.jar`, `*-javadoc.jar`, `*-plain.jar` if present).

On the server, **all** techno-club JARs go into `/srv/minecraft/plugins/` (Paper loads both support and lesson plugins from there).

## Change detection

Compare `github.event.before` (or `main`’s previous commit) to `github.sha`:

| Change set | Build / deploy |
|------------|----------------|
| Files under `plugins/<name>/` or `lessons/<name>/` | That unit only |
| Shared build/CI files (root Gradle wrapper/settings if added, `.github/workflows/deploy.yml`, etc.) | **All** units |
| `workflow_dispatch` with `deploy_all=true` (default for manual) | **All** units |
| `workflow_dispatch` with `deploy_all=false` | Changed only (same as push) |
| No matching changes | Skip build, rsync, and restart (job succeeds) |

When at least one JAR is deployed, always perform a full graceful restart (Paper does not reliably hot-swap plugin JARs).

## Workflow steps

Trigger: `push` to `main`, and `workflow_dispatch` (boolean input `deploy_all`, default `true`).

Concurrency: group `deploy-technoclub`, `cancel-in-progress: false` (never cancel a mid-restart deploy).

Environment: GitHub Environment `technoclub` (holds secrets/vars below).

1. **Checkout** full history depth needed for diff (`fetch-depth: 0` or `2` as required).
2. **Detect units** to build (paths above).
3. **Setup Temurin JDK 21**; for each unit, run `./gradlew build` (or `gradlew` in that directory).
4. **Stage JARs** into `_deploy/` (flat directory of final plugin JARs).
5. **Tailscale** via `tailscale/github-action@v4`:
   - `oauth-client-id` / `oauth-secret` from environment
   - `tags: tag:ci-technoclub`
6. **Configure SSH**: write `DEPLOY_SSH_KEY`, pin `SSH_KNOWN_HOSTS`, `IdentitiesOnly=yes`, `StrictHostKeyChecking=yes`.
7. **rsync** `_deploy/*.jar` → `${SSH_USER}@${SSH_HOST}:/srv/minecraft/plugins/`  
   - Do **not** `--delete` the remote plugins directory (preserve third-party JARs and configs).
8. **Restart:**  
   `ssh … 'sudo /usr/local/bin/restart-technoclub.sh'`
9. Fail the job if restart exits non-zero.

Align naming with zeenome where practical (`TAILSCALE_OAUTH_*`, deploy SSH key), but use a **separate** OAuth client, SSH key, and CI tag.

## Server-side runtime (already provisioned)

| Item | Value |
|------|--------|
| Host MagicDNS | `aztec-validator` |
| Tailscale tag | `tag:technoclub` |
| Deploy OS user | `tcdeploy` (pubkey auth only; MFA skipped for this user) |
| Plugin write path | `/srv/minecraft/plugins` (group `tcdeploy`, mode `775`) |
| Process owner | `danimbrogno` |
| Process manager | GNU **screen** session `minecraft` (not tmux) |
| Start command | `cd /srv/minecraft && ./startup.sh` → `java -Xms4096M -Xmx4096M -jar server.jar --nogui` |
| Restart entrypoint | `/usr/local/bin/restart-technoclub.sh` (root-owned) |
| Sudoers | `tcdeploy ALL=(root) NOPASSWD: /usr/local/bin/restart-technoclub.sh` |

### Restart script behavior

1. As `danimbrogno`, if screen session `minecraft` exists: `say` deploy warning, then `stop`.
2. Wait up to 120s for `java.*server.jar` owned by `danimbrogno` to exit; fail if still running.
3. Quit a dead screen session if needed.
4. `screen -dmS minecraft bash -lc "cd /srv/minecraft && exec ./startup.sh"`.

Canonical copy of this script lives in-repo under `scripts/remote-restart.sh` for review/drift control; the live server path remains `/usr/local/bin/restart-technoclub.sh`.

### SSH / MFA notes

Human users keep Google MFA via:

```text
Match User *,!tcdeploy
    AuthenticationMethods publickey,keyboard-interactive:pam
```

`tcdeploy` uses `AuthenticationMethods publickey` only, plus PAM:

```text
auth [success=done default=ignore] pam_succeed_if.so user = tcdeploy
auth required pam_google_authenticator.so
```

## Tailscale isolation

| Identity | May dial |
|----------|----------|
| `autogroup:member` | Full tailnet (existing policy) |
| `tag:ci` (zeenome) | `tag:dev:22` only |
| `tag:ci-technoclub` | `tag:technoclub:22` only |
| `tag:technoclub` | No outbound grants (not a grant `src`) |

OAuth client for techno-club: writable **Auth keys** scope only; tag **`tag:ci-technoclub` only**. Do not reuse zeenome’s OAuth client.

Disable key expiry on `aztec-validator` to avoid lockouts.

## GitHub Environment: `technoclub`

Create Environment **`technoclub`** on the repo. Workflow job sets `environment: technoclub`.

### Variables

| Name | Example / how to obtain |
|------|-------------------------|
| `TAILSCALE_OAUTH_CLIENT_ID` | OAuth client ID (techno-club client) |
| `SSH_HOST` | `aztec-validator` |
| `SSH_USER` | `tcdeploy` |
| `SSH_KNOWN_HOSTS` | From a machine on the tailnet: `ssh-keyscan -H aztec-validator` (use the full line(s) printed) |

### Secrets

| Name | Value |
|------|--------|
| `TAILSCALE_OAUTH_CLIENT_SECRET` | OAuth client secret |
| `DEPLOY_SSH_KEY` | Private key file contents for `tcdeploy` (e.g. `~/tcdeploy_github`), including `-----BEGIN … KEY-----` lines |

> Naming: `DEPLOY_SSH_KEY` matches zeenome’s deploy workflow for familiarity. Do **not** paste zeenome’s key; use the techno-club `tcdeploy` key only.

### How to set `SSH_KNOWN_HOSTS`

On cayamant (or any tailnet member):

```bash
ssh-keyscan -H aztec-validator 2>/dev/null
```

Paste the output into the environment variable (one or more lines). Prefer this over disabling host key checks.

### How to set `DEPLOY_SSH_KEY`

```bash
cat ~/tcdeploy_github
```

Paste the entire private key into the GitHub secret. Ensure no extra indentation; a trailing newline is fine.

## Failure handling

| Failure | Behavior |
|---------|----------|
| Build fails | Job fails; no deploy/restart |
| Tailscale join fails | Job fails; no deploy |
| rsync fails | Job fails; no restart |
| Restart script non-zero | Job fails (server may be down — operator checks screen/Java) |
| Nothing to deploy | Job succeeds; skip Tailscale/rsync/restart |

No automatic rollback of JARs in v1 (keep previous JARs on disk only if rsync never ran; overwritten JARs are replaced in place).

## Testing plan

1. `workflow_dispatch` with `deploy_all=true` after a stub/support plugin builds cleanly.
2. Confirm JARs appear under `/srv/minecraft/plugins` with expected names.
3. Confirm screen session `minecraft` returns and `server.jar` is listening.
4. Push a change to a single unit; confirm only that unit builds in logs.
5. Confirm zeenome deploy still works and techno-club CI cannot SSH to `tag:dev`.

## Implementation deliverables

1. `.github/workflows/deploy.yml`
2. `scripts/remote-restart.sh` (canonical server script)
3. Short ops note in README or `docs/` linking this spec and the GitHub Environment setup table
4. Operator applies GitHub Environment values (secrets/vars) before first green deploy

## Open follow-ups (out of scope)

- Rename zeenome `tag:ci` → `tag:ci-zeenome` for clearer naming
- Tighten `autogroup:member → *` grants later
- Docker Compose migration with persistent world bind-mounts
```

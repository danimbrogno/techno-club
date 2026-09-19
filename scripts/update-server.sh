#!/usr/bin/env bash
# Deployed to /srv/minecraft/bin/update-server.sh on aztec-validator by
# .github/workflows/deploy.yml. Edit here, not on the server.
#
# Updates the Paper server jar and third-party plugins (Geyser, Floodgate, ...)
# to their latest *stable* releases, restarting only when something changed.
#
# Plugins built from this repo (region-lock, club-support, lessons/*) are
# deployed by .github/workflows/deploy.yml and are never touched here.
#
# Usage:
#   ./update-server.sh                     # check + apply, restart if changed
#   ./update-server.sh --dry-run           # report only, change nothing
#   ./update-server.sh --allow-version-bump  # permit a Minecraft version change
#   ./update-server.sh --paper-version 26.2  # pin a specific Paper version
#   ./update-server.sh --only Geyser       # limit to one manifest entry
#   ./update-server.sh --force             # update even with players online
#   ./update-server.sh --no-restart        # stage + install, skip the restart
set -euo pipefail

SERVER_DIR="${SERVER_DIR:-/srv/minecraft}"
SESSION="${SESSION:-minecraft}"
START_CMD="${START_CMD:-./startup.sh}"
SERVER_JAR="${SERVER_JAR:-server.jar}"
STOP_TIMEOUT="${STOP_TIMEOUT:-120}"
START_TIMEOUT="${START_TIMEOUT:-180}"
WARN_SECONDS="${WARN_SECONDS:-60}"   # in-game warning before a restart
BACKUP_KEEP="${BACKUP_KEEP:-5}"      # backup sets to retain
JAVA_BIN="${JAVA_BIN:-java}"   # cron has a minimal PATH; override if java is not on it
USER_AGENT="technoclub-updater/1.0 (+https://github.com/danimbrogno/techno-club)"

PAPER_VERSION="${PAPER_VERSION:-latest}"   # "latest" = newest stable, or e.g. 26.2
PAPER_SEARCH_DEPTH=8                       # versions to consider when hunting for stable

# ---------------------------------------------------------------------------
# Plugin manifest: name|kind|spec|filename|glob
#
#   kind=geyser    spec=<project>:<platform>   (GeyserMC download API)
#   kind=modrinth  spec=<slug>[:<loader>]      (loader defaults to "paper")
#   kind=url       spec=<download url>         (plain direct download)
#
#   filename = what the jar is called in plugins/
#   glob     = matches the jar(s) this entry owns, so stale, differently
#              versioned copies are retired instead of double-loaded
# ---------------------------------------------------------------------------
PLUGINS=(
  "Geyser|geyser|geyser:spigot|Geyser-Spigot.jar|Geyser-Spigot*.jar"
  "Floodgate|geyser|floodgate:spigot|Floodgate-Spigot.jar|Floodgate-Spigot*.jar"
  #
  # Geyser and Floodgate are the only third-party plugins on this server; spark
  # ships inside Paper, and everything else in plugins/ is built from our own
  # repos. Add more here as needed, e.g.:
  # "ViaVersion|modrinth|viaversion|ViaVersion.jar|ViaVersion*.jar"
  # "EssentialsX|modrinth|essentialsx|EssentialsX.jar|EssentialsX-[0-9]*.jar"
)

DRY_RUN=false
FORCE=false
NO_RESTART=false
ALLOW_VERSION_BUMP=false
ONLY=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true ;;
    --force) FORCE=true ;;
    --no-restart) NO_RESTART=true ;;
    --allow-version-bump) ALLOW_VERSION_BUMP=true ;;
    --paper-version) PAPER_VERSION="${2:?--paper-version needs a value}"; shift ;;
    --only) ONLY="${2:?--only needs a value}"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

PLUGIN_DIR="$SERVER_DIR/plugins"
STAGE_DIR="$SERVER_DIR/.update-staging"
BACKUP_ROOT="$SERVER_DIR/backups"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$BACKUP_ROOT/$STAMP"
CHANGED=()

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

cleanup() { rm -rf "$STAGE_DIR"; }
trap cleanup EXIT

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1 (try: sudo apt install -y $2)"; }

api() {
  curl -fsSL --retry 3 --retry-delay 5 --max-time 120 -H "User-Agent: $USER_AGENT" "$@"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

java_major() {
  local v
  v="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/')"
  [[ "$v" =~ ^[0-9]+$ ]] || die "could not determine the installed Java version"
  printf '%s' "$v"
}

# --- server process control -------------------------------------------------

screen_exists() { screen -ls 2>/dev/null | grep -qE "[0-9]+\.${SESSION}[[:space:]]"; }
server_running() { pgrep -u "$(id -un)" -f "java.*${SERVER_JAR}" >/dev/null 2>&1; }
console() { screen -S "$SESSION" -X stuff "$1$(printf '\r')"; }

players_online() {
  local logf="$SERVER_DIR/logs/latest.log" before count
  server_running || { printf '0'; return; }
  [[ -f "$logf" ]] || { printf 'unknown'; return; }
  before="$(wc -c < "$logf")"
  console "list"
  sleep 3
  count="$(tail -c "+$((before + 1))" "$logf" | grep -oE 'There are [0-9]+' | tail -1 | awk '{print $3}')"
  printf '%s' "${count:-unknown}"
}

stop_server() {
  if ! screen_exists; then
    log "screen session '$SESSION' not found; nothing to stop"
    return 0
  fi
  if (( WARN_SECONDS > 0 )) && server_running; then
    console "say Server update: restarting in ${WARN_SECONDS}s."
    sleep "$WARN_SECONDS"
  fi
  log "Saving world and stopping server..."
  console "save-all"
  sleep 5
  console "stop"
  local i
  for ((i = 0; i < STOP_TIMEOUT; i++)); do
    server_running || { log "Server process exited."; break; }
    sleep 1
  done
  server_running && die "server still running after ${STOP_TIMEOUT}s; aborting before any jar swap"
  screen_exists && { screen -S "$SESSION" -X quit || true; sleep 1; }
  return 0
}

start_server() {
  local logf="$SERVER_DIR/logs/latest.log" start_ts i mtime
  start_ts="$(date +%s)"
  log "Starting server..."
  screen -dmS "$SESSION" bash -lc "cd '$SERVER_DIR' && exec $START_CMD"
  for ((i = 0; i < START_TIMEOUT; i++)); do
    if [[ -f "$logf" ]]; then
      mtime="$(stat -c %Y "$logf" 2>/dev/null || stat -f %m "$logf" 2>/dev/null || echo 0)"
      # only trust the log once this run has written to it
      if (( mtime >= start_ts )) && tail -80 "$logf" | grep -q "Done ("; then
        log "Server is up."
        return 0
      fi
    fi
    sleep 1
  done
  server_running || die "server failed to start; check $logf"
  log "WARNING: no 'Done (' in the log after ${START_TIMEOUT}s, but the process is alive."
}

# --- install helpers --------------------------------------------------------

# install_file <staged> <target path> <glob dir> <glob>
install_file() {
  local staged="$1" target="$2" gdir="$3" glob="$4" old
  mkdir -p "$BACKUP_DIR"
  shopt -s nullglob
  for old in "$gdir"/$glob; do
    [[ -f "$old" ]] || continue
    log "  backing up $(basename "$old")"
    mv "$old" "$BACKUP_DIR/"
  done
  shopt -u nullglob
  mv "$staged" "$target"
  chmod 644 "$target"
  log "  installed $(basename "$target")"
}

prune_backups() {
  local dirs=()
  shopt -s nullglob
  dirs=("$BACKUP_ROOT"/*/)
  shopt -u nullglob
  (( ${#dirs[@]} > BACKUP_KEEP )) || return 0
  local n=$(( ${#dirs[@]} - BACKUP_KEEP )) d
  for d in $(printf '%s\n' "${dirs[@]}" | sort | head -n "$n"); do
    log "Pruning old backup $(basename "$d")"
    rm -rf "$d"
  done
}

# --- Paper ------------------------------------------------------------------

paper_pick_version() {
  local java_min versions v status build_json channel
  local jmajor; jmajor="$(java_major)"

  if [[ "$PAPER_VERSION" != "latest" ]]; then
    printf '%s' "$PAPER_VERSION"
    return 0
  fi

  versions="$(api https://fill.papermc.io/v3/projects/paper/versions \
    | jq -r --argjson jm "$jmajor" '
        .versions[]
        | select(.version.support.status == "SUPPORTED")
        | select(.version.id | test("-(rc|pre)") | not)
        | select(.version.java.version.minimum <= $jm)
        | .version.id')"

  [[ -n "$versions" ]] || die "no supported Paper version runs on Java $jmajor (upgrade the JDK)"

  local count=0
  while IFS= read -r v; do
    (( count++ < PAPER_SEARCH_DEPTH )) || break
    channel="$(api "https://fill.papermc.io/v3/projects/paper/versions/$v/builds/latest" | jq -r '.channel')"
    if [[ "$channel" == "STABLE" ]]; then
      printf '%s' "$v"
      return 0
    fi
    log "Skipping Paper $v (latest build is $channel, not STABLE)"
  done <<< "$versions"

  die "no Paper version in the newest $PAPER_SEARCH_DEPTH had a STABLE build"
}

current_paper_version() {
  local jar="$SERVER_DIR/$SERVER_JAR"
  [[ -f "$jar" ]] || { printf 'none'; return; }
  unzip -p "$jar" version.json 2>/dev/null | jq -r '.id // "unknown"' 2>/dev/null || printf 'unknown'
}

update_paper() {
  local version build json url sha name jmin jmajor current jar
  jar="$SERVER_DIR/$SERVER_JAR"
  version="$(paper_pick_version)"
  json="$(api "https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest")"
  build="$(jq -r '.id' <<< "$json")"
  url="$(jq -r '.downloads."server:default".url' <<< "$json")"
  sha="$(jq -r '.downloads."server:default".checksums.sha256' <<< "$json")"
  name="$(jq -r '.downloads."server:default".name' <<< "$json")"
  [[ "$url" == http* ]] || die "Paper API returned no download URL for $version"

  jmin="$(api https://fill.papermc.io/v3/projects/paper/versions \
    | jq -r --arg v "$version" '.versions[] | select(.version.id == $v) | .version.java.version.minimum')"
  jmajor="$(java_major)"
  if [[ -n "$jmin" && "$jmin" != "null" ]] && (( jmin > jmajor )); then
    die "Paper $version needs Java >= $jmin but Java $jmajor is installed"
  fi

  current="$(current_paper_version)"
  log "Paper: installed $current, latest stable $version build $build"

  if [[ -f "$jar" ]] && [[ "$(sha256_of "$jar")" == "$sha" ]]; then
    log "  already up to date"
    return 0
  fi

  if [[ "$current" != "none" && "$current" != "unknown" && "$current" != "$version" ]] \
     && [[ "$ALLOW_VERSION_BUMP" != true && "$PAPER_VERSION" == "latest" ]]; then
    log "  HOLD: this is a Minecraft version change ($current -> $version), not just a build update."
    log "  Confirm Geyser/Floodgate and the club plugins support $version, then re-run with --allow-version-bump."
    HELD=true
    return 0
  fi

  if [[ "$DRY_RUN" == true ]]; then
    log "  [dry-run] would install $name"
    CHANGED+=("Paper $current -> $version build $build (dry-run)")
    return 0
  fi

  log "  downloading $name"
  api -o "$STAGE_DIR/$SERVER_JAR" "$url"
  [[ "$(sha256_of "$STAGE_DIR/$SERVER_JAR")" == "$sha" ]] || die "checksum mismatch on $name"
  PAPER_STAGED=true
  PAPER_DESC="Paper $current -> $version build $build"
  CHANGED+=("$PAPER_DESC")
}

# --- plugins ----------------------------------------------------------------

resolve_geyser() {   # <project>:<platform> -> url
  local project="${1%%:*}" platform="${1##*:}"
  printf 'https://download.geysermc.org/v2/projects/%s/versions/latest/builds/latest/downloads/%s' \
    "$project" "$platform"
}

resolve_modrinth() { # <slug>[:<loader>] -> url
  local slug="${1%%:*}" loader="paper"
  [[ "$1" == *:* ]] && loader="${1##*:}"
  api "https://api.modrinth.com/v2/project/$slug/version?loaders=%5B%22$loader%22%5D" \
    | jq -r '[.[] | select(.version_type == "release")][0].files[] | select(.primary) | .url'
}

update_plugin() {
  local entry="$1" name kind spec file glob url staged target
  IFS='|' read -r name kind spec file glob <<< "$entry"
  [[ -z "$ONLY" || "$ONLY" == "$name" ]] || return 0

  case "$kind" in
    geyser)   url="$(resolve_geyser "$spec")" ;;
    modrinth) url="$(resolve_modrinth "$spec")" ;;
    url)      url="$spec" ;;
    *) die "unknown manifest kind '$kind' for $name" ;;
  esac
  [[ "$url" == http* ]] || die "could not resolve a download URL for $name"

  staged="$STAGE_DIR/$file"
  log "$name: fetching $url"
  api -o "$staged" "$url"
  [[ -s "$staged" ]] || die "empty download for $name"

  target="$PLUGIN_DIR/$file"
  if [[ -f "$target" ]] && [[ "$(sha256_of "$target")" == "$(sha256_of "$staged")" ]]; then
    log "  already up to date"
    rm -f "$staged"
    return 0
  fi

  if [[ "$DRY_RUN" == true ]]; then
    log "  [dry-run] would install $file"
    CHANGED+=("$name (dry-run)")
    rm -f "$staged"
    return 0
  fi

  PLUGIN_STAGED+=("$name|$staged|$target|$glob")
  CHANGED+=("$name")
}

# --- main -------------------------------------------------------------------

need_cmd curl curl
need_cmd jq jq
need_cmd unzip unzip
need_cmd screen screen
need_cmd flock util-linux
command -v "$JAVA_BIN" >/dev/null 2>&1 || die "java not found on PATH (set JAVA_BIN=/path/to/java, or run this from a login shell)"

[[ -d "$SERVER_DIR" ]] || die "server dir $SERVER_DIR not found"
[[ -w "$SERVER_DIR" ]] || die "$SERVER_DIR is not writable by $(id -un)"
[[ -d "$PLUGIN_DIR" ]] || die "plugin dir $PLUGIN_DIR not found"

exec 9>"$SERVER_DIR/.update.lock"
flock -n 9 || die "another update is already running"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR" "$BACKUP_ROOT"

log "=== Techno Club server update ($( [[ "$DRY_RUN" == true ]] && echo dry-run || echo apply )) ==="

PAPER_STAGED=false
HELD=false
PAPER_DESC=""
PLUGIN_STAGED=()

if [[ -z "$ONLY" || "$ONLY" == "Paper" ]]; then
  update_paper
fi

for entry in "${PLUGINS[@]}"; do
  update_plugin "$entry"
done

if (( ${#CHANGED[@]} == 0 )); then
  if [[ "$HELD" == true ]]; then
    log "Nothing applied: the only update available is a held Minecraft version bump."
  else
    log "Everything is already up to date; no restart needed."
  fi
  exit 0
fi

log "Updates ready:"
printf '  - %s\n' "${CHANGED[@]}" >&2

if [[ "$DRY_RUN" == true ]]; then
  log "Dry run complete; nothing was changed."
  exit 0
fi

if [[ "$FORCE" != true ]]; then
  online="$(players_online)"
  if [[ "$online" != "0" && "$online" != "unknown" ]]; then
    log "SKIP: $online player(s) online. Staged updates discarded; will retry next run (or use --force)."
    exit 0
  fi
fi

WAS_RUNNING=false
server_running && WAS_RUNNING=true

if [[ "$NO_RESTART" != true ]]; then
  stop_server
fi

if [[ "$PAPER_STAGED" == true ]]; then
  log "Installing Paper jar"
  install_file "$STAGE_DIR/$SERVER_JAR" "$SERVER_DIR/$SERVER_JAR" "$SERVER_DIR" "$SERVER_JAR"
fi

for staged_entry in "${PLUGIN_STAGED[@]:-}"; do
  [[ -n "$staged_entry" ]] || continue
  IFS='|' read -r sname sfile starget sglob <<< "$staged_entry"
  log "Installing $sname"
  install_file "$sfile" "$starget" "$PLUGIN_DIR" "$sglob"
done

prune_backups

if [[ "$NO_RESTART" == true ]]; then
  log "--no-restart: jars swapped, server left as-is (restart manually to load them)."
elif [[ "$WAS_RUNNING" == true ]]; then
  start_server
else
  log "Server was not running before the update; leaving it stopped."
fi

log "Update complete: ${CHANGED[*]}"
log "Backups in $BACKUP_DIR"

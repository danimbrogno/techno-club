#!/usr/bin/env bash
# Deployed to /srv/minecraft/bin/migrate-server.sh on aztec-validator by
# .github/workflows/deploy.yml. Edit here, not on the server.
#
# Moves the Techno Club server from this machine to a new one over SSH.
# Run it on the OLD server, as the user that owns the server process.
# See docs/server-migration.md for the full procedure.
#
# Usage:
#   ./migrate-server.sh check   user@new-host    # is the new host ready? changes nothing
#   ./migrate-server.sh sync    user@new-host    # copy the server while it keeps running
#   ./migrate-server.sh sync    user@new-host --test-boot   # ...then boot and stop it there
#   ./migrate-server.sh cutover user@new-host    # stop here, final copy, start there, move cron
#
# Options:
#   -i <keyfile>   SSH identity for the new host (or configure it in ~/.ssh/config)
#   --force        cut over even with players online
#   --overwrite    allow syncing into a non-empty remote dir this script did not create
set -euo pipefail

SERVER_DIR="${SERVER_DIR:-/srv/minecraft}"
REMOTE_DIR="${REMOTE_DIR:-$SERVER_DIR}"
SESSION="${SESSION:-minecraft}"
START_CMD="${START_CMD:-./startup.sh}"
SERVER_JAR="${SERVER_JAR:-server.jar}"
STOP_TIMEOUT="${STOP_TIMEOUT:-120}"
START_TIMEOUT="${START_TIMEOUT:-180}"
WARN_SECONDS="${WARN_SECONDS:-60}"   # in-game warning before the cutover stop
JAVA_BIN="${JAVA_BIN:-java}"
DEPLOY_USER="${DEPLOY_USER:-tcdeploy}"
RESTART_HELPER="${RESTART_HELPER:-/usr/local/bin/restart-technoclub.sh}"

REMOTE_MARKER=".migrated-from"   # in REMOTE_DIR: "<old host> <time> synced|live"
LOCAL_MARKER="MIGRATED-TO"       # in SERVER_DIR once cutover is done

CMD=""
DEST=""
IDENTITY=""
FORCE=false
OVERWRITE=false
TEST_BOOT=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i) IDENTITY="${2:?-i needs a key file}"; shift ;;
    --force) FORCE=true ;;
    --overwrite) OVERWRITE=true ;;
    --test-boot) TEST_BOOT=true ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;;
    *)
      if [[ -z "$CMD" ]]; then CMD="$1"
      elif [[ -z "$DEST" ]]; then DEST="$1"
      else echo "Unexpected argument: $1" >&2; exit 2
      fi
      ;;
  esac
  shift
done

case "$CMD" in
  check|sync|cutover) ;;
  *) sed -n '9,19p' "$0" >&2; exit 2 ;;
esac
[[ -n "$DEST" ]] || { echo "Missing destination (user@new-host)" >&2; exit 2; }

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
q() { printf '%q' "$1"; }

need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1 (try: sudo apt install -y $2)"; }

java_major() {
  local v
  v="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/')"
  [[ "$v" =~ ^[0-9]+$ ]] || die "could not determine the installed Java version"
  printf '%s' "$v"
}

# Prints the -Xmx heap size in MB from a start script, or nothing.
heap_mb_from() {
  local x
  x="$(grep -oE -- '-Xmx[0-9]+[kKmMgG]' "$1" 2>/dev/null | tail -1)" || true
  [[ -n "$x" ]] || return 0
  x="${x#-Xmx}"
  case "${x: -1}" in
    g|G) echo $(( ${x%?} * 1024 )) ;;
    m|M) echo "${x%?}" ;;
    k|K) echo $(( ${x%?} / 1024 )) ;;
  esac
}

# --- SSH to the new host ----------------------------------------------------

# One multiplexed connection, so a password or key prompt happens once.
CTRL_DIR="$(mktemp -d)"
SSH_OPTS=(-o ControlMaster=auto -o "ControlPath=$CTRL_DIR/%C" -o ControlPersist=10m -o ServerAliveInterval=30)
[[ -n "$IDENTITY" ]] && SSH_OPTS+=(-i "$IDENTITY" -o IdentitiesOnly=yes)

SAVE_OFF=false

cleanup() {
  if [[ "$SAVE_OFF" == true ]]; then console "save-on" || true; fi
  ssh "${SSH_OPTS[@]}" -O exit "$DEST" >/dev/null 2>&1 || true
  rm -rf "$CTRL_DIR"
}
trap cleanup EXIT

# Prepended to every remote script. $1..$4 are REMOTE_DIR, SESSION,
# SERVER_JAR and START_CMD; rrun's own arguments follow as $1, $2, ...
REMOTE_PRELUDE='set -uo pipefail
d=$1 session=$2 jar=$3 start_cmd=$4; shift 4
running() { pgrep -u "$(id -un)" -f "java.*$jar" >/dev/null 2>&1; }
screen_exists() { screen -ls 2>/dev/null | grep -qE "[0-9]+\.${session}[[:space:]]"; }
console() { screen -S "$session" -X stuff "$1$(printf "\r")"; }'

# rrun [args...] <<'EOF' ... EOF — run a bash script on the new host in a
# login shell (so java resolves the same way it does for cron).
rrun() {
  local cmd="bash -l -s --" a
  for a in "$REMOTE_DIR" "$SESSION" "$SERVER_JAR" "$START_CMD" "$@"; do cmd+=" $(q "$a")"; done
  { printf '%s\n' "$REMOTE_PRELUDE"; cat; } | ssh "${SSH_OPTS[@]}" "$DEST" "$cmd"
}

# --- local server control (same behavior as update-server.sh) ---------------

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
    console "say Server is moving to a new machine in ${WARN_SECONDS}s. Back in a few minutes!"
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
  server_running && die "server still running after ${STOP_TIMEOUT}s; aborting before copying"
  screen_exists && { screen -S "$SESSION" -X quit || true; sleep 1; }
  return 0
}

start_local_server() {
  log "Restarting the server here..."
  screen -dmS "$SESSION" bash -lc "cd '$SERVER_DIR' && exec $START_CMD"
}

# --- remote server control --------------------------------------------------

# Returns 0 once "Done (" appears, 3 if the process is alive but never logged
# it, 1 if it died.
start_remote_server() {
  log "Starting server on $DEST (waiting up to ${START_TIMEOUT}s)..."
  rrun "$START_TIMEOUT" <<'EOF'
start_ts=$(date +%s)
screen -dmS "$session" bash -lc "cd '$d' && exec $start_cmd"
logf="$d/logs/latest.log"
for ((i = 0; i < $1; i++)); do
  if [[ -f $logf ]] && (( $(stat -c %Y "$logf") >= start_ts )) && tail -80 "$logf" | grep -q 'Done ('; then
    exit 0
  fi
  sleep 1
done
running && exit 3
exit 1
EOF
}

stop_remote_server() {
  log "Stopping server on $DEST..."
  rrun "$STOP_TIMEOUT" <<'EOF'
screen_exists || exit 0
console "save-all"; sleep 5; console "stop"
for ((i = 0; i < $1; i++)); do running || break; sleep 1; done
running && exit 1
screen -S "$session" -X quit >/dev/null 2>&1 || true
EOF
}

# --- checks -----------------------------------------------------------------

# Reads key=value facts about the new host into R[].
declare -A R
gather_remote_facts() {
  local k v
  while IFS='=' read -r k v; do
    [[ -n "$k" ]] && R["$k"]="$v"
  done < <(rrun "$DEPLOY_USER" "$RESTART_HELPER" "$REMOTE_MARKER" <<'EOF'
have() { command -v "$1" >/dev/null 2>&1 && echo yes || echo no; }
echo "host=$(hostname)"
for c in java screen rsync jq unzip flock curl crontab tailscale; do echo "cmd_$c=$(have "$c")"; done
if command -v java >/dev/null 2>&1; then
  echo "java=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/')"
fi
echo "dir_exists=$([[ -d $d ]] && echo yes || echo no)"
echo "dir_writable=$([[ -w $d ]] && echo yes || echo no)"
echo "running=$(running && echo yes || echo no)"
echo "screen=$(screen_exists && echo yes || echo no)"
echo "deploy_user=$(id "$1" >/dev/null 2>&1 && echo yes || echo no)"
echo "restart_helper=$([[ -x $2 ]] && echo yes || echo no)"
echo "marker=$(head -1 "$d/$3" 2>/dev/null)"
# bin/ and an empty plugins/ are what setup-new-server.sh creates
foreign=$(ls -A "$d" 2>/dev/null | grep -vxE "bin|plugins|lost\+found|${3//./\\.}" | head -1)
[[ -z $foreign && -n $(ls -A "$d/plugins" 2>/dev/null) ]] && foreign=plugins/$(ls -A "$d/plugins" | head -1)
echo "foreign=$foreign"
p=$d; while [[ ! -d $p ]]; do p=$(dirname "$p"); done
echo "avail_kb=$(df -Pk "$p" | awk 'NR==2 {print $4}')"
echo "mem_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
echo "used_kb=$(du -sk "$d" 2>/dev/null | cut -f1)"
echo "plugins_group=$(stat -c %G "$d/plugins" 2>/dev/null)"
echo "bin_group=$(stat -c %G "$d/bin" 2>/dev/null)"
EOF
  )
  [[ -n "${R[host]:-}" ]] || die "could not reach $DEST over SSH"
}

# preflight <strict>: strict=true also requires what CI needs after cutover.
preflight() {
  local strict="$1" errors=() warnings=() c local_java need_kb local_kb
  log "Checking $DEST (${REMOTE_DIR})..."
  gather_remote_facts

  for c in java screen rsync jq unzip flock curl crontab; do
    [[ "${R[cmd_$c]:-}" == yes ]] || errors+=("'$c' is not installed (run setup-new-server.sh there)")
  done

  local_java="$(java_major)"
  if [[ "${R[cmd_java]:-}" == yes ]] && ! [[ "${R[java]:-}" =~ ^[0-9]+$ && ${R[java]} -ge $local_java ]]; then
    errors+=("Java ${R[java]:-?} on the new host is older than Java $local_java here")
  fi

  if [[ "${R[dir_exists]:-}" != yes ]]; then
    errors+=("$REMOTE_DIR does not exist (run setup-new-server.sh there)")
  elif [[ "${R[dir_writable]:-}" != yes ]]; then
    errors+=("$REMOTE_DIR is not writable by the SSH user")
  fi

  [[ "${R[running]:-}" == yes || "${R[screen]:-}" == yes ]] &&
    errors+=("a Minecraft server (or '$SESSION' screen session) is already running on the new host")

  if [[ "${R[marker]:-}" == *" live" ]]; then
    errors+=("the new host is already live (${R[marker]:-}); syncing again would overwrite its worlds")
  elif [[ -z "${R[marker]:-}" && -n "${R[foreign]:-}" && "$OVERWRITE" != true ]]; then
    errors+=("$REMOTE_DIR already has files (e.g. '${R[foreign]:-}') this script did not put there; use --overwrite if they can be replaced")
  fi

  local_kb="$(du -sk "$SERVER_DIR" | cut -f1)"
  need_kb=$(( local_kb * 11 / 10 - ${R[used_kb]:-0} ))
  (( need_kb > ${R[avail_kb]:-0} )) &&
    errors+=("not enough disk: need ~$(( need_kb / 1024 )) MB more, $(( ${R[avail_kb]:-0} / 1024 )) MB free")

  # The heap from startup.sh (-Xmx4096M) plus ~1 GB for the JVM and the OS.
  local heap_mb need_mb
  heap_mb="$(heap_mb_from "$SERVER_DIR/${START_CMD#./}")"
  if [[ -n "$heap_mb" ]]; then
    need_mb=$(( heap_mb + 1024 ))
    (( ${R[mem_kb]:-0} / 1024 >= need_mb )) ||
      errors+=("only $(( ${R[mem_kb]:-0} / 1024 )) MB RAM; startup.sh gives Java ${heap_mb} MB, so the new host needs ~${need_mb} MB")
  fi

  local ci_needs=()
  [[ "${R[deploy_user]:-}" == yes ]] || ci_needs+=("user '$DEPLOY_USER' does not exist (run setup-new-server.sh there)")
  [[ "${R[restart_helper]:-}" == yes ]] || ci_needs+=("$RESTART_HELPER is missing (run setup-new-server.sh there)")
  [[ "${R[cmd_tailscale]:-}" == yes ]] || ci_needs+=("tailscale is not installed (CI reaches the server over Tailscale)")
  if (( ${#ci_needs[@]} > 0 )); then
    if [[ "$strict" == true ]]; then errors+=("${ci_needs[@]}"); else warnings+=("${ci_needs[@]}"); fi
  fi

  local sub g rg
  for sub in plugins bin; do
    [[ -d "$SERVER_DIR/$sub" ]] || continue
    g="$(stat -c %G "$SERVER_DIR/$sub")"
    rg="${R[${sub}_group]:-}"
    [[ -n "$rg" && "$rg" != "$g" ]] &&
      warnings+=("$REMOTE_DIR/$sub has group '$rg', here it is '$g' (sync tries to fix this)")
  done

  log "New host: ${R[host]:-}, Java ${R[java]:-none}, $(( ${R[mem_kb]:-0} / 1024 )) MB RAM, $(( ${R[avail_kb]:-0} / 1024 )) MB disk free; this server is $(( local_kb / 1024 )) MB"
  local w e
  for w in "${warnings[@]}"; do log "  WARN: $w"; done
  for e in "${errors[@]}"; do log "  FAIL: $e"; done
  (( ${#errors[@]} == 0 )) || die "${#errors[@]} problem(s) on the new host; fix them and re-run"
  log "New host looks ready."
}

# --- copying ----------------------------------------------------------------

copy_server() {
  log "Copying $SERVER_DIR/ -> $DEST:$REMOTE_DIR/ ..."
  # No owner/group: files land owned by the SSH user. Directory groups are
  # matched afterwards so tcdeploy can still write plugins/ and bin/.
  rsync -aH --delete --no-owner --no-group --info=stats1,progress2 \
    --exclude=/.update.lock --exclude=/.update-staging/ \
    --exclude="/$REMOTE_MARKER" --exclude="/$LOCAL_MARKER" \
    -e "ssh ${SSH_OPTS[*]}" \
    "$SERVER_DIR/" "$DEST:$REMOTE_DIR/" || return 1

  local sub groups=()
  for sub in plugins bin; do
    [[ -d "$SERVER_DIR/$sub" ]] && groups+=("$sub:$(stat -c %G "$SERVER_DIR/$sub")")
  done
  rrun "${groups[@]}" <<'EOF'
for pair in "$@"; do
  sub=${pair%%:*} g=${pair#*:}
  [[ $(stat -c %G "$d/$sub") == "$g" ]] && continue
  chgrp "$g" "$d/$sub" 2>/dev/null ||
    echo "WARN: could not set group '$g' on $d/$sub; run: sudo chgrp $g $d/$sub" >&2
done
EOF
}

write_remote_marker() {
  rrun "$REMOTE_MARKER" "$(hostname) $(date -Is) $1" <<'EOF'
printf '%s\n' "$2" > "$d/$1"
EOF
}

# --- cron -------------------------------------------------------------------

update_cron_lines() { crontab -l 2>/dev/null | grep -E '^[^#].*update-server\.sh' || true; }

move_cron() {
  local lines
  lines="$(update_cron_lines)"
  if [[ -z "$lines" ]]; then
    log "WARN: no update-server.sh cron entry here; set one up on the new host (docs/server-updates.md)"
    return 0
  fi
  lines="${lines//$SERVER_DIR/$REMOTE_DIR}"
  log "Installing the update cron entry on $DEST"
  rrun "$lines" <<'EOF'
current=$(crontab -l 2>/dev/null || true)
if grep -qE '^[^#].*update-server\.sh' <<< "$current"; then
  echo "cron entry already present on the new host" >&2
  exit 0
fi
{ [[ -n $current ]] && printf '%s\n' "$current"; printf '%s\n' "$1"; } | crontab -
EOF
  log "Disabling the update cron entry here"
  crontab -l | sed -E '/^[^#].*update-server\.sh/ s/^/#MIGRATED /' | crontab -
}

# --- commands ---------------------------------------------------------------

cmd_check() {
  preflight false
}

cmd_sync() {
  preflight false

  if server_running; then
    # Pause autosave and flush, so the copy is a consistent snapshot that the
    # test boot can load. The trap turns saving back on if anything fails.
    log "Pausing autosave for the copy (players can keep playing)"
    console "save-off"
    SAVE_OFF=true
    console "save-all flush"
    sleep 10
  fi

  copy_server
  write_remote_marker synced

  if [[ "$SAVE_OFF" == true ]]; then
    console "save-on"
    SAVE_OFF=false
    log "Autosave resumed"
  fi

  if [[ "$TEST_BOOT" == true ]]; then
    local rc=0
    start_remote_server || rc=$?
    case "$rc" in
      0) log "Test boot OK: the server came up on $DEST." ;;
      3) log "WARN: test boot is running but never logged 'Done ('; check $REMOTE_DIR/logs/latest.log there" ;;
      *) die "test boot failed on $DEST; check $REMOTE_DIR/logs/latest.log there" ;;
    esac
    stop_remote_server || die "could not stop the test server on $DEST; stop it by hand before cutover"
    log "Test server stopped. Its world changes will be replaced at cutover."
  fi

  log "Sync complete. Re-run 'sync' any time; run 'cutover' when ready."
}

cmd_cutover() {
  preflight true

  if [[ "$FORCE" != true ]]; then
    local online
    online="$(players_online)"
    if [[ "$online" != "0" && "$online" != "unknown" ]]; then
      die "$online player(s) online; try again later or use --force"
    fi
  fi

  stop_server

  if ! copy_server; then
    log "Copy failed; the new host was not started."
    start_local_server
    die "cutover aborted; this server is back up and nothing moved"
  fi

  local rc=0
  start_remote_server || rc=$?
  case "$rc" in
    0) log "Server is up on $DEST." ;;
    3) log "WARN: the server is running on $DEST but never logged 'Done ('; check it before telling players" ;;
    *)
      log "The server did not start on $DEST. This server is stopped but unchanged."
      log "To roll back: $0 check $DEST  (to see what is wrong), then here:"
      log "  screen -dmS $SESSION bash -lc \"cd '$SERVER_DIR' && exec $START_CMD\""
      die "cutover failed at startup on the new host"
      ;;
  esac

  write_remote_marker live
  move_cron

  # Keep this copy from coming back up (a CI deploy still pointed here runs
  # the restart helper) and diverging from the live world.
  local start_file="$SERVER_DIR/${START_CMD#./}"
  if [[ -f "$start_file" ]]; then
    mv "$start_file" "$start_file.migrated"
    log "Renamed $(basename "$start_file") -> $(basename "$start_file").migrated so this copy cannot start"
  fi
  printf '%s %s\n' "$DEST" "$(date -Is)" > "$SERVER_DIR/$LOCAL_MARKER"

  cat >&2 <<EOF

Cutover complete: ${R[host]:-} is now the Techno Club server.

Still to do by hand:
  1. GitHub -> Settings -> Environments -> dev:
       SSH_HOST        = ${R[host]:-}
       SSH_KNOWN_HOSTS = output of: ssh-keyscan -H ${R[host]:-}
     then run the Deploy workflow with deploy_all to prove CI works.
  2. Point DNS / tell players about the new address (Java TCP 25565,
     Bedrock via Geyser on UDP 19132 unless configured otherwise).
  3. Keep this machine as-is for a while as a rollback copy.
EOF
}

# --- main -------------------------------------------------------------------

need_cmd rsync rsync
need_cmd screen screen
need_cmd flock util-linux
need_cmd ssh openssh-client
command -v "$JAVA_BIN" >/dev/null 2>&1 || die "java not found on PATH (set JAVA_BIN=/path/to/java)"
[[ -d "$SERVER_DIR" ]] || die "server dir $SERVER_DIR not found"

if [[ -f "$SERVER_DIR/$LOCAL_MARKER" && "$CMD" != check ]]; then
  die "this server was already migrated to $(cat "$SERVER_DIR/$LOCAL_MARKER"); refusing to copy a stale world over it"
fi

# Same lock as update-server.sh, so a cron update cannot run mid-migration.
exec 9>"$SERVER_DIR/.update.lock"
flock -n 9 || die "an update or migration is already running"

log "=== Techno Club server migration: $CMD -> $DEST ==="
"cmd_$CMD"

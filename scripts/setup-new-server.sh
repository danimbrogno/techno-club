#!/usr/bin/env bash
# One-time root setup for a new Techno Club server, before running
# migrate-server.sh from the old one. Safe to re-run.
#
# Recreates what aztec-validator has outside /srv/minecraft:
#   - packages the server, update script and migration need
#   - users: danimbrogno (runs the server), tcdeploy (CI deploys)
#   - /srv/minecraft (danimbrogno 755), plugins/ + bin/ (danimbrogno:tcdeploy 775)
#   - /usr/local/bin/restart-technoclub.sh + tcdeploy's sudoers rule for it
#   - firewall ports, if ufw is active
#
# Usage (on the new machine, next to remote-restart.sh):
#   sudo ./setup-new-server.sh \
#     --deploy-key "ssh-ed25519 AAAA... ci"       # CI's public key, for tcdeploy
#     --migrate-key "ssh-ed25519 AAAA... old"     # old server danimbrogno's public key
#
# Options:
#   --deploy-key <key>    add to tcdeploy's authorized_keys (repeatable)
#   --migrate-key <key>   add to danimbrogno's authorized_keys (repeatable)
#   --skip-java           do not install/check Java
set -euo pipefail

MC_USER="${MC_USER:-danimbrogno}"
DEPLOY_USER="${DEPLOY_USER:-tcdeploy}"
SERVER_DIR="${SERVER_DIR:-/srv/minecraft}"
RESTART_HELPER="${RESTART_HELPER:-/usr/local/bin/restart-technoclub.sh}"
SUDOERS_FILE="${SUDOERS_FILE:-/etc/sudoers.d/tcdeploy-technoclub}"
JAVA_MIN="${JAVA_MIN:-25}"                          # Paper 26.x needs Java 25+
JAVA_PKG="${JAVA_PKG:-openjdk-${JAVA_MIN}-jre-headless}"
JAVA_PORT="${JAVA_PORT:-25565}"
BEDROCK_PORT="${BEDROCK_PORT:-19132}"

DEPLOY_KEYS=()
MIGRATE_KEYS=()
SKIP_JAVA=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --deploy-key) DEPLOY_KEYS+=("${2:?--deploy-key needs a public key}"); shift ;;
    --migrate-key) MIGRATE_KEYS+=("${2:?--migrate-key needs a public key}"); shift ;;
    --skip-java) SKIP_JAVA=true ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo"
command -v apt-get >/dev/null 2>&1 || die "this script expects a Debian/Ubuntu machine (apt-get)"

HERE="$(cd "$(dirname "$0")" && pwd)"
[[ -f "$HERE/remote-restart.sh" ]] || die "remote-restart.sh not found next to this script ($HERE)"

java_major() {
  java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/'
}

# add_keys <user> <key>...: append keys not already in ~user/.ssh/authorized_keys
add_keys() {
  local user="$1" home key file; shift
  home="$(getent passwd "$user" | cut -d: -f6)"
  file="$home/.ssh/authorized_keys"
  install -d -o "$user" -g "$user" -m 700 "$home/.ssh"
  touch "$file"
  for key in "$@"; do
    if grep -qxF "$key" "$file"; then
      log "  key already present for $user"
    else
      printf '%s\n' "$key" >> "$file"
      log "  added key for $user"
    fi
  done
  chown "$user:$user" "$file"
  chmod 600 "$file"
}

# --- packages ---------------------------------------------------------------

log "Installing packages"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  screen rsync jq unzip curl cron util-linux procps openssh-server sudo >/dev/null
systemctl enable --now cron >/dev/null 2>&1 || true

if [[ "$SKIP_JAVA" != true ]]; then
  if command -v java >/dev/null 2>&1 && (( $(java_major) >= JAVA_MIN )); then
    log "Java $(java_major) already installed"
  else
    log "Installing $JAVA_PKG"
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$JAVA_PKG" >/dev/null ||
      die "could not install $JAVA_PKG. Install Java $JAVA_MIN+ another way (e.g. Temurin from adoptium.net), then re-run with --skip-java"
    (( $(java_major) >= JAVA_MIN )) || die "java on PATH is still $(java_major); need $JAVA_MIN+"
  fi
fi

# --- users ------------------------------------------------------------------

for user in "$MC_USER" "$DEPLOY_USER"; do
  if id "$user" >/dev/null 2>&1; then
    log "User $user exists"
  else
    useradd -m -s /bin/bash "$user"
    log "Created user $user"
  fi
done

# danimbrogno must be in tcdeploy's group so migrate-server.sh can give
# plugins/ and bin/ that group after copying.
usermod -aG "$DEPLOY_USER" "$MC_USER"

(( ${#DEPLOY_KEYS[@]} == 0 )) || add_keys "$DEPLOY_USER" "${DEPLOY_KEYS[@]}"
(( ${#MIGRATE_KEYS[@]} == 0 )) || add_keys "$MC_USER" "${MIGRATE_KEYS[@]}"

# --- directories (same owners/modes as aztec-validator) ---------------------

log "Creating $SERVER_DIR"
install -d -o "$MC_USER" -g "$MC_USER" -m 755 "$SERVER_DIR"
install -d -o "$MC_USER" -g "$DEPLOY_USER" -m 775 "$SERVER_DIR/plugins" "$SERVER_DIR/bin"

# --- restart helper + sudoers -----------------------------------------------

log "Installing $RESTART_HELPER"
install -o root -g root -m 755 "$HERE/remote-restart.sh" "$RESTART_HELPER"

log "Installing sudoers rule $SUDOERS_FILE"
tmp="$(mktemp)"
printf '%s ALL=(root) NOPASSWD: %s\n' "$DEPLOY_USER" "$RESTART_HELPER" > "$tmp"
visudo -cqf "$tmp" || { rm -f "$tmp"; die "generated sudoers rule failed validation"; }
install -o root -g root -m 440 "$tmp" "$SUDOERS_FILE"
rm -f "$tmp"

# --- firewall ---------------------------------------------------------------

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
  log "Opening $JAVA_PORT/tcp (Java) and $BEDROCK_PORT/udp (Bedrock) in ufw"
  ufw allow "$JAVA_PORT/tcp" >/dev/null
  ufw allow "$BEDROCK_PORT/udp" >/dev/null
else
  log "ufw not active; make sure $JAVA_PORT/tcp and $BEDROCK_PORT/udp are reachable (cloud firewall too)"
fi

# --- what is left -----------------------------------------------------------

todo=()
command -v tailscale >/dev/null 2>&1 ||
  todo+=("Install Tailscale and join the tailnet (https://tailscale.com/download/linux); ACLs must let tag:ci-technoclub SSH in")
has_keys() { [[ -s "$(getent passwd "$1" | cut -d: -f6)/.ssh/authorized_keys" ]]; }
has_keys "$DEPLOY_USER" ||
  todo+=("Add CI's public key: re-run with --deploy-key \"\$(sudo cat ~$DEPLOY_USER/.ssh/authorized_keys)\" copied from the old server")
has_keys "$MC_USER" ||
  todo+=("Let the old server in: re-run with --migrate-key \"<old server's ~$MC_USER/.ssh/id_*.pub>\"")

log "Setup done."
if (( ${#todo[@]} > 0 )); then
  log "Still to do:"
  printf '  - %s\n' "${todo[@]}" >&2
fi
log "Next, on the old server: /srv/minecraft/bin/migrate-server.sh check $MC_USER@$(hostname)"

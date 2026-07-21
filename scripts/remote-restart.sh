#!/usr/bin/env bash
# Canonical copy of /usr/local/bin/restart-technoclub.sh on aztec-validator.
# Keep in sync when changing restart behavior.
set -euo pipefail

MC_USER="danimbrogno"
SESSION="minecraft"
SERVER_DIR="/srv/minecraft"
START_CMD="./startup.sh"
STOP_TIMEOUT=120

run_as_mc() {
  sudo -u "$MC_USER" "$@"
}

screen_exists() {
  run_as_mc screen -ls 2>/dev/null | grep -qE "[0-9]+\.${SESSION}[[:space:]]"
}

echo "Requesting graceful stop..."
if screen_exists; then
  run_as_mc screen -S "$SESSION" -X stuff "say Techno Club deploy: restarting server...$(printf '\r')"
  sleep 2
  run_as_mc screen -S "$SESSION" -X stuff "stop$(printf '\r')"
else
  echo "screen session '$SESSION' not found; will start fresh"
fi

echo "Waiting up to ${STOP_TIMEOUT}s for server to exit..."
for ((i = 0; i < STOP_TIMEOUT; i++)); do
  if ! pgrep -u "$MC_USER" -f 'java.*server\.jar' >/dev/null 2>&1; then
    echo "Server process exited."
    break
  fi
  sleep 1
done

if pgrep -u "$MC_USER" -f 'java.*server\.jar' >/dev/null 2>&1; then
  echo "ERROR: server still running after ${STOP_TIMEOUT}s" >&2
  exit 1
fi

if screen_exists; then
  run_as_mc screen -S "$SESSION" -X quit || true
  sleep 1
fi

echo "Starting server..."
run_as_mc screen -dmS "$SESSION" bash -lc "cd '$SERVER_DIR' && exec $START_CMD"
echo "Restart requested."

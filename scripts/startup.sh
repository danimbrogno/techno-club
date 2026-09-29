#!/usr/bin/env sh
# Canonical copy of /srv/minecraft/startup.sh on aztec-validator.
# Keep in sync when changing JVM flags. migrate-server.sh copies the live one.

java -Xms4096M -Xmx4096M -jar server.jar --nogui

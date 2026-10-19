#!/bin/sh
# Runs inside the "updater" service: pulls a newer FileCloud image and recreates
# the app container when the image changed. Database and files are untouched.
INTERVAL=${UPDATE_INTERVAL:-21600}
compose() { docker compose --project-directory /project "$@"; }

while true; do
  echo "[updater] $(date -u +%FT%TZ) checking for a newer image"
  if compose pull --quiet filecloud; then
    compose up -d --no-deps --no-build filecloud
    docker image prune -f >/dev/null 2>&1
  else
    echo "[updater] pull failed — is FILECLOUD_IMAGE set to a registry image?"
  fi
  sleep "$INTERVAL"
done

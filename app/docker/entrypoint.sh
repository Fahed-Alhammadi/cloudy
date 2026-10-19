#!/bin/sh
# Cloudy all-in-one entrypoint: runs PostgreSQL and the Cloudy server in one container.
# Runs as root only long enough to prepare users/permissions, then drops to APP_USER (uid:gid).
#
# Environment (all optional, usually set in .env):
#   APP_USER      uid:gid for both processes (default 1000:1000)
#   DATABASE_URL  use an external PostgreSQL instead of the embedded one
#   DB_DIR        embedded database directory (default /var/lib/cloudy/db — keep on a local disk)
#   DATA_DIR      user files (default /data — may be an NFS mount)
set -eu

APP_USER=${APP_USER:-1000:1000}
UID_=${APP_USER%%:*}
GID_=${APP_USER##*:}
DB_DIR=${DB_DIR:-/var/lib/cloudy/db}
DATA_DIR=${DATA_DIR:-/data}
SOCK_DIR=/run/cloudy
PG_MAJOR=17
PG_BIN=/usr/libexec/postgresql$PG_MAJOR
export DATA_DIR

log() { echo "[cloudy] $*"; }

case "$UID_$GID_" in
  *[!0-9]*|'') echo "APP_USER must look like 1000:1000 (got '$APP_USER')" >&2; exit 1 ;;
esac
if [ "$UID_" = 0 ]; then echo "APP_USER must not be root (0)" >&2; exit 1; fi

# Make sure the uid/gid have names (PostgreSQL refuses to run as a nameless user).
if [ "$(id -u)" = 0 ]; then
  getent group "$GID_" >/dev/null || addgroup -g "$GID_" cloudy
  GROUP_NAME=$(getent group "$GID_" | cut -d: -f1)
  getent passwd "$UID_" >/dev/null || adduser -D -H -h /tmp -s /sbin/nologin -u "$UID_" -G "$GROUP_NAME" cloudy
  RUN="su-exec $UID_:$GID_"
else
  RUN=""   # already started as a non-root user (e.g. docker run --user)
fi

# Storage: the database dir is ours (local disk) — fix ownership freely. For user files, only try to
# fix ownership when the app could not write there (NFS with root_squash will simply refuse).
mkdir -p "$SOCK_DIR" "$DATA_DIR"
if [ "$(id -u)" = 0 ]; then
  chown "$UID_:$GID_" "$SOCK_DIR"
  if ! su-exec "$UID_:$GID_" test -w "$DATA_DIR"; then
    log "fixing ownership of $DATA_DIR for $UID_:$GID_"
    chown -R "$UID_:$GID_" "$DATA_DIR" 2>/dev/null || true
  fi
fi

pg_pid=""
if [ -z "${DATABASE_URL:-}" ]; then
  mkdir -p "$DB_DIR"
  if [ "$(id -u)" = 0 ]; then
    chown -R "$UID_:$GID_" "$DB_DIR"
    chmod 700 "$DB_DIR"
  fi

  if [ -s "$DB_DIR/PG_VERSION" ]; then
    have=$(cat "$DB_DIR/PG_VERSION")
    if [ "$have" != "$PG_MAJOR" ]; then
      echo "Database in $DB_DIR is PostgreSQL $have but this image runs $PG_MAJOR." >&2
      echo "Back it up with the previous image (pg_dump) and restore it into a fresh DB_PATH." >&2
      exit 1
    fi
  else
    log "initialising database in $DB_DIR"
    $RUN "$PG_BIN/initdb" -D "$DB_DIR" -U cloudy --auth=trust --encoding=UTF8 --locale=C.UTF-8 >/dev/null
  fi

  # Unix socket only: the database is not reachable from outside the container.
  $RUN "$PG_BIN/postgres" -D "$DB_DIR" -c listen_addresses='' -c unix_socket_directories="$SOCK_DIR" \
    -c hba_file=/etc/cloudy/pg_hba.conf &
  pg_pid=$!

  i=0
  until $RUN "$PG_BIN/pg_isready" -q -h "$SOCK_DIR" -U cloudy -d postgres; do
    i=$((i + 1))
    if [ $i -gt 60 ] || ! kill -0 "$pg_pid" 2>/dev/null; then echo "PostgreSQL failed to start" >&2; exit 1; fi
    sleep 1
  done
  # Databases created by older two-container installs already have it.
  # One-time migration for databases created before the project was named Cloudy (role/database "filecloud").
  if ! $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U cloudy -d postgres -tAc "SELECT 1" >/dev/null 2>&1 \
     && $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U filecloud -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
    log "migrating database from its previous name"
    $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U filecloud -d postgres -qc "CREATE ROLE cloudy SUPERUSER LOGIN"
    if $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U cloudy -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='filecloud'" | grep -q 1; then
      $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U cloudy -d postgres -qc "ALTER DATABASE filecloud RENAME TO cloudy"
      $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U cloudy -d postgres -qc "ALTER DATABASE cloudy OWNER TO cloudy"
    fi
  fi
  if ! $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U cloudy -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='cloudy'" | grep -q 1; then
    $RUN "$PG_BIN/psql" -h "$SOCK_DIR" -U cloudy -d postgres -qc "CREATE DATABASE cloudy"
  fi
  export DATABASE_URL="postgres://cloudy@localhost/cloudy?host=$SOCK_DIR"
  log "PostgreSQL $PG_MAJOR ready"
fi

$RUN node --disable-warning=ExperimentalWarning /app/src/server.ts &
app_pid=$!

shutdown() {
  log "stopping"
  kill -TERM "$app_pid" 2>/dev/null || true
  wait "$app_pid" 2>/dev/null || true
  if [ -n "$pg_pid" ]; then
    kill -INT "$pg_pid" 2>/dev/null || true   # PostgreSQL "fast" shutdown
    wait "$pg_pid" 2>/dev/null || true
  fi
  exit 0
}
trap shutdown TERM INT

# If either process dies, stop the other and exit so Docker's restart policy kicks in.
while kill -0 "$app_pid" 2>/dev/null && { [ -z "$pg_pid" ] || kill -0 "$pg_pid" 2>/dev/null; }; do
  sleep 2 &
  wait $! || true
done
log "a process exited unexpectedly"
kill -TERM "$app_pid" 2>/dev/null || true
[ -n "$pg_pid" ] && kill -INT "$pg_pid" 2>/dev/null || true
wait 2>/dev/null || true
exit 1

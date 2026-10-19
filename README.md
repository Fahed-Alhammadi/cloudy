# FileCloud

Your own cloud for files, sharing, calendars and contacts. You run it yourself: one Docker Compose stack, a
browser app, and a native Android app that updates over the air.

## Features

- **Files:** drag-and-drop upload (whole folders too), resumable uploads, previews for images, video, audio and
  PDF, a text editor, versions, trash, favorites, recent files, search, and download a folder as ZIP.
- **Sharing:**
  - with users or groups, read-only or editable;
  - public links with a password, an expiry date, and optional upload into the shared folder.
- **Calendar and contacts:** web apps, plus CalDAV/CardDAV sync with Thunderbird, DAVx⁵, Apple
  Calendar/Contacts and others.
- **WebDAV:** mount your files in Finder, Windows Explorer, rclone or Cyberduck.
- **Android app:**
  - browse files, preview them and share links;
  - keep files available offline;
  - back up photos automatically;
  - calendar and contacts;
  - **over-the-air updates** delivered by your own server.
- **Administration:** users, groups, quotas, app passwords, server update checks, and publishing Android
  releases.
- **Self-updating server:** an optional updater service pulls new images and restarts the app.

## Quick start

```sh
cp .env.example .env          # set DB_PASSWORD and ADMIN_PASSWORD
docker compose up -d --build
```

Open http://localhost:8080 and sign in as `ADMIN_USER` / `ADMIN_PASSWORD`. If `ADMIN_PASSWORD` is empty, a
random one is printed in `docker compose logs app`.

## Production

FileCloud serves plain HTTP on `APP_PORT` (default 8080). Put it behind your reverse proxy for TLS, and set
`TRUST_PROXY=true` in `.env`. The proxy must:
- forward `Host`, `X-Forwarded-For` and `X-Forwarded-Proto`;
- allow unlimited request bodies and long timeouts (uploads arrive in 8 MB chunks; downloads stream);
- pass all HTTP methods through (WebDAV uses PROPFIND, MKCOL, MOVE, COPY, LOCK, REPORT…).

Set `APP_BIND=127.0.0.1` if the proxy runs on the same host.

Example nginx location:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 0;
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_read_timeout 3600s;
}
```

**Back up** the `db-data` and `app-data` volumes together.

## Updates

**Server.**
1. Run a published image: set `FILECLOUD_IMAGE=ghcr.io/fahed-alhammadi/filecloud:latest` in `.env`, then run
   `docker compose pull && docker compose up -d`.
2. Optional, see new versions in the admin page: set `UPDATE_REPO=Fahed-Alhammadi/filecloud`.
3. Install updates automatically:

   ```sh
   docker compose --profile autoupdate up -d     # checks every UPDATE_INTERVAL seconds (default 6 h)
   ```

The database schema migrates itself at startup. Your data stays in the volumes. The release workflow in
`.github/workflows/release.yml` publishes the image to GitHub Container Registry when you push a `v*` tag.

**Android app.** Publish a new APK and every installed app offers the update:

```sh
mobile/android/build-apk.sh              # bump versionCode/versionName in app/build.gradle.kts first
FILECLOUD_URL=https://cloud.example.com FILECLOUD_TOKEN=<admin app password> \
  mobile/android/publish-apk.sh "What's new"
```

You can also upload it in *Administration → Android app*. Users download the first install from
`https://<your-server>/download/android`, which is also linked on the login page. See
[mobile/README.md](mobile/README.md) for details.

## Connecting other apps

Create an app password under *Settings → Connect apps and devices*, then use:

| Client | Address |
|---|---|
| WebDAV (Finder, Explorer, rclone, Cyberduck) | `https://<host>/dav/files/<username>/` |
| CalDAV / CardDAV (Thunderbird, DAVx⁵, Apple) | `https://<host>/dav/` (auto-discovered via `/.well-known/caldav` and `/.well-known/carddav`) |

## Configuration (`.env`)

| Variable | Default | Purpose |
|---|---|---|
| `DB_PASSWORD` | — (required) | PostgreSQL password |
| `ADMIN_USER` / `ADMIN_PASSWORD` | `admin` / random | First administrator (created on first start) |
| `APP_PORT` / `APP_BIND` | `8080` / `0.0.0.0` | Published port and interface |
| `TRUST_PROXY` | `false` | Honor `X-Forwarded-*` headers (only behind a proxy) |
| `TRASH_RETENTION_DAYS` | `30` | Days before trashed items are deleted |
| `MAX_VERSIONS` | `20` | Versions kept per file |
| `FILECLOUD_IMAGE` | `filecloud:local` | Image to run (set a registry image to receive updates) |
| `UPDATE_REPO` | — | GitHub `owner/repo` checked for new server releases |
| `UPDATE_INTERVAL` | `21600` | Seconds between automatic update checks (updater profile) |

## Development

| Task | Command |
|---|---|
| Type-check the server | `cd app && npm install && npm run typecheck` |
| End-to-end tests (stack running) | `python3 tests/e2e.py http://localhost:8080 admin '<admin password>'` |
| Android debug build | `cd mobile/android && ./gradlew assembleDebug` (JDK 17+, Android SDK 35) |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how FileCloud works inside.

## Project layout

```
app/                 server (Node.js/TypeScript) and the HTML5 web app (app/public)
mobile/android/      native Android app (Kotlin, Jetpack Compose)
docs/                architecture notes
scripts/             auto-update loop used by the updater service
tests/               end-to-end API/WebDAV/CalDAV/CardDAV tests
.github/workflows/   CI and release automation (Docker image + APK)
```

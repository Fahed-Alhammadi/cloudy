# FileCloud

## Overview
Self-hosted cloud for files, sharing, calendars and contacts: Node.js/TypeScript server + plain-HTML5 web app +
native Android app with over-the-air updates. Ships as ONE container (Node 24 server + embedded PostgreSQL 17,
started by `app/docker/entrypoint.sh`), configured entirely from `.env`. TLS/reverse proxy is external.

## Structure
- `docker-compose.yml`, `.env.example` — service `filecloud` from `ghcr.io/fahed-alhammadi/filecloud:latest` (`env_file: .env`); update with `docker compose pull && docker compose up -d`; `.env` is git-ignored.
- `app/Dockerfile`, `app/docker/` — all-in-one image: `entrypoint.sh` (root → user setup, initdb, start postgres on the
  `/run/filecloud` socket, then node as APP_USER; supervises both), `pg_hba.conf` (local trust only).
- `deploy/docker-compose.yml` — builds `app/` from source (`build: ../app`, `env_file: ../.env`); run from repo root with
  `-f deploy/docker-compose.yml --env-file .env`. Same project name/volumes as the root file. Keep the two in sync.
- `.github/workflows/` — `ci.yml` (typecheck, e2e against compose, Android debug build), `release.yml` (on `v*` tag: GHCR image + signed APK on the GitHub release).
- `docs/ARCHITECTURE.md` — design notes; `docs/logo.svg` — app logo (used in README). `tests/e2e.py` — end-to-end test (stdlib Python) against a running stack.
- `app/src/server.ts` — entry: HTTP dispatch, CSRF guard, static app serving, hourly jobs (trash/upload/session expiry).
- `app/src/config.ts` — env config incl. `version` (APP_VERSION or package.json).
- `app/src/schema.sql` — whole DB schema, applied idempotently at every start (no migration tool).
- `app/src/files.ts` — file model: node tree in Postgres, immutable blobs on disk, size/etag propagation,
  trash, versions, quota, share-based access checks. All file mutations go through here.
- `app/src/download.ts` — Range downloads (inline-safe types only) and streaming store-only ZIP.
- `app/src/auth.ts` / `users.ts` — scrypt passwords, cookie sessions, bearer device tokens, Basic auth + app passwords, rate limit, user lifecycle.
- `app/src/groupware.ts` — calendar/address-book store shared by DAV and JSON API (ical.js parsing, sync change log).
- `app/src/dav/` — `server.ts` WebDAV (files) + CalDAV/CardDAV under `/dav`; `xml.ts` namespace-aware XML + multistatus.
- `app/src/api/` — JSON API: `account.ts` (login, device tokens, me, app passwords, admin), `files.ts` (nodes,
  chunked uploads, trash, versions), `shares.ts` (shares + public links), `groupware.ts`, `updates.ts`
  (Android release publishing, `/api/app/android`, `/download/android`).
- `app/public/` — HTML5 front end, no build step: `index.html`, `public.html` (`/s/<token>`), `js/*.js` (one module per view), `css/app.css`.
- `mobile/android/` — Kotlin/Compose app `io.filecloud.app`: `data/` (Api, Session, Offline, AppUpdater), `work/Workers.kt`
  (photo backup, offline sync, daily update check), `ui/` screens. `build-apk.sh` → `mobile/dist/`; `publish-apk.sh` → server.

## Tech Stack
Server: Node 24 running TypeScript directly (type stripping), `pg`, `ical.js`, `fast-xml-parser`; Postgres 17.
Web: vanilla JS modules, `<dialog>`, drag-and-drop, XHR chunk upload. Android: Kotlin 2.1, Compose (BOM 2024.12),
OkHttp, kotlinx.serialization, Coil, Media3, WorkManager; AGP 8.7, compileSdk 35, minSdk 26.

## Commands
- Run published image: `cp .env.example .env` (edit) → `docker compose up -d`; UI on `http://localhost:${APP_PORT:-8080}`
- Build from source: `docker compose -f deploy/docker-compose.yml --env-file .env up -d --build`
- Update server: `docker compose pull && docker compose up -d`
- Logs: `docker logs -f filecloud` · Reset all data: `docker compose down -v`
- Type-check: `cd app && npm install && npm run typecheck`
- E2E test (stack running): `python3 tests/e2e.py http://localhost:8080 admin <password>`
- Android: `mobile/android/build-apk.sh` (needs JDK 17+, Android SDK 35); publish OTA:
  `FILECLOUD_URL=… FILECLOUD_TOKEN=<admin app password> mobile/android/publish-apk.sh "notes"`

## Conventions
- TypeScript must stay *erasable* (no enums/namespaces/parameter properties); imports use `.ts` extensions.
- DB access via `q`/`one`/`tx` in `db.ts`; raw SQL, parameterized only. New tables/columns go in `schema.sql` with `IF NOT EXISTS`.
- API errors: throw `HttpError(status, message)`; JSON API returns `{error}`. Mutating `/api` calls need header `X-FC-Request: 1`.
- Access rules: read/edit on a node = owner or user/group share on it or an ancestor; changing/removing a node
  needs edit on its parent (`requireParentEdit`). Hidden nodes return 404, not 403.
- Storage: `APP_USER` (entrypoint drops to it via su-exec), `DATA_PATH` → `/data` (may be NFS), `DB_PATH` → `/var/lib/filecloud/db`
  (local disk). `ensureDirs()` probes writability and exits with a clear FATAL message. All of /data must be one filesystem.
- PostgreSQL major is pinned in the image (`PG_MAJOR` in entrypoint); a mismatch with `DB_PATH/PG_VERSION` stops startup — plan
  a dump/restore (or ship both versions + pg_upgrade) before bumping it.
- `TRUST_PROXY` gates all use of X-Forwarded-For/-Proto (`http.ts`); keep it false when the port is exposed directly.
- Android authenticates with `POST /api/auth/token` → bearer token (an app password); keep `data/Models.kt` in sync with the JSON API.
- Releases: bump `app/package.json` version (server) and `versionCode`/`versionName` in `mobile/android/app/build.gradle.kts`;
  versionCode must always increase (server rejects lower ones). Tag `vX.Y.Z` to run the release workflow.
- Android signing key `mobile/android/filecloud-release.keystore` + `keystore.properties` are git-ignored and must be backed up;
  CI signs with the same key from repository secrets.
- Front end: build DOM with `h()` from `ui.js` (never innerHTML with data); each view exports `renderX(main, ctx)`.
- Known limits: ZIP has no ZIP64 (<4 GB, <65k entries); recurring events edited as a whole series.

## Change Log
- 2026-10-19: `.env` optional in both compose files (`env_file: required: false`); README zero-config `docker run` quick start.
- 2026-10-19: Removed server OTA (updater service, GitHub release check, `UPDATE_REPO`/`UPDATE_INTERVAL`); server updates are
  `docker compose pull`. OTA kept for the Android APK only.
- 2026-10-19: Swapped compose files: root `docker-compose.yml` = published image (+ inline updater); `deploy/docker-compose.yml`
  = build from source. Removed `scripts/auto-update.sh`; CI builds via the deploy file.
- 2026-10-19: Single all-in-one container (server + embedded PostgreSQL 17 via tini/entrypoint); `DB_PASSWORD` removed,
  `DB_PATH`/`DATABASE_URL` added, compose service renamed `filecloud` with `env_file: .env`. Existing volumes migrate as-is.
- 2026-10-19: Added `deploy/docker-compose.yml` (image-only deployment); root compose now passes `UPDATE_REPO` to the app.
- 2026-10-19: `.env` options `APP_USER` (run as uid:gid, default 1000:1000) and `DATA_PATH` (user-file storage, e.g. host NFS mount);
  startup writability check.
- 2026-10-19: CI fix — setup-android v3 failed installing legacy `tools`; now v4 with explicit SDK packages.
  All actions moved to Node 24 majors; runners pinned to ubuntu-24.04.
- 2026-10-19: Renamed project to FileCloud (package `io.filecloud.app`, new signing key, DB `filecloud`); removed iOS app.
  Over-the-air updates: server version/update check (`UPDATE_REPO`), `updater` compose service, Android APK
  publishing + in-app/daily update check. GitHub CI + release workflows. Server 1.1.0, Android 1.1.0 (code 2).
- 2026-10-19: Android app (files, previews, sharing, offline, photo backup, calendar, contacts); device-token auth.
- 2026-10-19: App port published directly for an external reverse proxy; `TRUST_PROXY` guards forwarded headers.
- 2026-10-19: Initial build — files, uploads, trash, versions, sharing, WebDAV, CalDAV/CardDAV, HTML5 UI, Compose stack.

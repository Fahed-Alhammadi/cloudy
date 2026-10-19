# FileCloud

## Overview
Self-hosted cloud for files, sharing, calendars and contacts: Node.js/TypeScript server + plain-HTML5 web app +
native Android app with over-the-air updates. Runs as a Docker Compose stack: app (Node 24, published on
APP_PORT) → Postgres 17. TLS/reverse proxy is external.

## Structure
- `docker-compose.yml`, `.env.example` — stack (`db`, `app`, optional `updater` under profile `autoupdate`); `.env` holds secrets (git-ignored).
- `scripts/auto-update.sh` — loop run by the `updater` service: `compose pull app` + recreate when the image changed.
- `.github/workflows/` — `ci.yml` (typecheck, e2e against compose, Android debug build), `release.yml` (on `v*` tag: GHCR image + signed APK on the GitHub release).
- `docs/ARCHITECTURE.md` — design notes. `tests/e2e.py` — end-to-end test (stdlib Python) against a running stack.
- `app/src/server.ts` — entry: HTTP dispatch, CSRF guard, static app serving, hourly jobs (trash/upload/session expiry).
- `app/src/config.ts` — env config incl. `version` (APP_VERSION or package.json) and `updateRepo`.
- `app/src/schema.sql` — whole DB schema, applied idempotently at every start (no migration tool).
- `app/src/files.ts` — file model: node tree in Postgres, immutable blobs on disk, size/etag propagation,
  trash, versions, quota, share-based access checks. All file mutations go through here.
- `app/src/download.ts` — Range downloads (inline-safe types only) and streaming store-only ZIP.
- `app/src/auth.ts` / `users.ts` — scrypt passwords, cookie sessions, bearer device tokens, Basic auth + app passwords, rate limit, user lifecycle.
- `app/src/groupware.ts` — calendar/address-book store shared by DAV and JSON API (ical.js parsing, sync change log).
- `app/src/dav/` — `server.ts` WebDAV (files) + CalDAV/CardDAV under `/dav`; `xml.ts` namespace-aware XML + multistatus.
- `app/src/api/` — JSON API: `account.ts` (login, device tokens, me, app passwords, admin), `files.ts` (nodes,
  chunked uploads, trash, versions), `shares.ts` (shares + public links), `groupware.ts`, `updates.ts`
  (server update check, Android release publishing, `/download/android`).
- `app/public/` — HTML5 front end, no build step: `index.html`, `public.html` (`/s/<token>`), `js/*.js` (one module per view), `css/app.css`.
- `mobile/android/` — Kotlin/Compose app `io.filecloud.app`: `data/` (Api, Session, Offline, AppUpdater), `work/Workers.kt`
  (photo backup, offline sync, daily update check), `ui/` screens. `build-apk.sh` → `mobile/dist/`; `publish-apk.sh` → server.

## Tech Stack
Server: Node 24 running TypeScript directly (type stripping), `pg`, `ical.js`, `fast-xml-parser`; Postgres 17.
Web: vanilla JS modules, `<dialog>`, drag-and-drop, XHR chunk upload. Android: Kotlin 2.1, Compose (BOM 2024.12),
OkHttp, kotlinx.serialization, Coil, Media3, WorkManager; AGP 8.7, compileSdk 35, minSdk 26.

## Commands
- Run stack: `cp .env.example .env` (edit) → `docker compose up -d --build`; UI on `http://localhost:${APP_PORT:-8080}`
- With auto-updates: set `FILECLOUD_IMAGE` to a registry image, then `docker compose --profile autoupdate up -d`
- Logs: `docker compose logs -f app` · Reset all data: `docker compose down -v`
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
- `TRUST_PROXY` gates all use of X-Forwarded-For/-Proto (`http.ts`); keep it false when the port is exposed directly.
- Android authenticates with `POST /api/auth/token` → bearer token (an app password); keep `data/Models.kt` in sync with the JSON API.
- Releases: bump `app/package.json` version (server) and `versionCode`/`versionName` in `mobile/android/app/build.gradle.kts`;
  versionCode must always increase (server rejects lower ones). Tag `vX.Y.Z` to run the release workflow.
- Android signing key `mobile/android/filecloud-release.keystore` + `keystore.properties` are git-ignored and must be backed up;
  CI signs with the same key from repository secrets.
- Front end: build DOM with `h()` from `ui.js` (never innerHTML with data); each view exports `renderX(main, ctx)`.
- Known limits: ZIP has no ZIP64 (<4 GB, <65k entries); recurring events edited as a whole series.

## Change Log
- 2026-10-19: Renamed project to FileCloud (package `io.filecloud.app`, new signing key, DB `filecloud`); removed iOS app.
  Over-the-air updates: server version/update check (`UPDATE_REPO`), `updater` compose service, Android APK
  publishing + in-app/daily update check. GitHub CI + release workflows. Server 1.1.0, Android 1.1.0 (code 2).
- 2026-10-19: Android app (files, previews, sharing, offline, photo backup, calendar, contacts); device-token auth.
- 2026-10-19: App port published directly for an external reverse proxy; `TRUST_PROXY` guards forwarded headers.
- 2026-10-19: Initial build — files, uploads, trash, versions, sharing, WebDAV, CalDAV/CardDAV, HTML5 UI, Compose stack.

# Cloudy

## Overview
Self-hosted cloud for files, sharing, calendars and contacts: Node.js/TypeScript server + plain-HTML5 web app +
native Android app with over-the-air updates. Ships as ONE container (Node 24 server + embedded PostgreSQL 17 +
LibreOffice for office previews, started by `app/docker/entrypoint.sh`), configured from `.env`. TLS/reverse proxy is external.

## Structure
- `docker-compose.yml`, `.env.example` — service `cloudy` from `ghcr.io/fahed-alhammadi/cloudy:latest` (`env_file: .env`); update with `docker compose pull && docker compose up -d`; `.env` is git-ignored.
- `app/Dockerfile`, `app/docker/` — all-in-one Debian 13 image: `entrypoint.sh` (root → user setup, initdb, start postgres on
  the `/run/cloudy` socket, then node as APP_USER; supervises both), `pg_hba.conf`.
- `deploy/docker-compose.yml` — builds `app/` from source (`build: ../app`, `env_file: ../.env`); run from repo root with
  `-f deploy/docker-compose.yml --env-file .env`. Same project name/volumes as the root file. Keep the two in sync.
- `.github/workflows/` — `ci.yml` (typecheck, e2e against compose, Android debug build), `release.yml` (on `v*` tag: GHCR image + signed APK on the GitHub release).
- `docs/ARCHITECTURE.md` — design notes; `docs/logo.svg` — app logo (used in README); `docs/privacy-policy.md` — app privacy policy
  for Google Play (keep it in sync with what the Android app accesses). `tests/e2e.py` — end-to-end test (stdlib Python) against a running stack.
- `app/src/server.ts` — entry: HTTP dispatch, CSRF guard, static app serving, hourly jobs (trash/upload/session expiry).
- `app/src/config.ts` — env config incl. `version` (APP_VERSION or package.json).
- `app/src/schema.sql` — whole DB schema, applied idempotently at every start (no migration tool).
- `app/src/files.ts` — file model: node tree in Postgres, immutable blobs on disk, size/etag propagation,
  trash, versions, quota, share-based access checks. All file mutations go through here.
- `app/src/download.ts` — Range downloads (inline-safe types only) and streaming store-only ZIP.
- `app/src/auth.ts` / `users.ts` — scrypt passwords, cookie sessions, bearer device tokens, Basic auth + app passwords, rate limit, user lifecycle.
- `app/src/totp.ts` — TOTP (RFC 6238) + recovery codes; 2FA routes in `api/account.ts` (`/api/me/2fa/*`), enforced in `auth.login()`.
- `app/src/webauthn.ts` — passkeys (CBOR/COSE parsing, challenges, signature checks, no deps); routes in `api/passkeys.ts`
  (`/api/passkeys/login*`, `/api/me/passkeys*`), login in `auth.loginWithPasskey()`; browser glue `public/js/passkey.js`.
- `app/src/events.ts` — live updates: LISTEN on `cloudy_changes` (triggers in schema.sql) → SSE `GET /api/events` `{folders, shares}`.
- `app/src/office.ts` — read-only office previews: `GET /api/nodes/:id/preview` = LibreOffice → PDF (queued, cached in
  `DATA_DIR/cache/previews/<id>-<etag>.pdf`), `GET /api/office` lists extensions. Web: `public/js/office.js` + viewer.
- `app/src/mail.ts` — SMTP settings (`settings.smtp`, admin `/api/admin/mail*`), `sendMail` (background) / `sendMailNow`,
  password reset (`/api/auth/forgot|reset|options`, `password_resets`), share + password-changed notifications.
- `app/src/contacts-import.ts` — parses uploaded contacts: vCard 2.1–4.0 (normalized to 3.0/4.0) and CSV (Google,
  Outlook, simple); routes `/api/addressbooks/:id/import|export` in `api/groupware.ts` (UID update, duplicate skip).
- `app/src/groupware.ts` — calendar/address-book store shared by DAV and JSON API (ical.js parsing, sync change log).
- `app/src/dav/` — `server.ts` WebDAV (files) + CalDAV/CardDAV under `/dav`; `xml.ts` namespace-aware XML + multistatus.
- `app/src/api/` — JSON API: `account.ts` (login, device tokens, me, app passwords, admin), `files.ts` (nodes,
  chunked uploads, trash, versions), `shares.ts` (shares + public links), `groupware.ts`. `/download/android` (server.ts)
  redirects to the latest GitHub release's `Cloudy.apk` (`APP_REPO`).
- `app/public/` — HTML5 front end, no build step: `index.html`, `public.html` (`/s/<token>`), `js/*.js` (one module per view), `css/app.css`.
- `mobile/android/` — Kotlin/Compose app `io.cloudy.app`: `data/` (Api, Session, Offline, ConnectionCheck = proxy/DAV diagnostics, ListCache = in-memory listings, AppUpdater = GitHub releases, Live = SSE + Office),
  `work/Workers.kt` (photo backup, offline sync, daily update check), `ui/` screens (PreviewScreen edits text files).
  `build-apk.sh` → `mobile/dist/`.

## Tech Stack
Server: Node 24 running TypeScript directly (type stripping), `pg`, `ical.js`, `fast-xml-parser`, `nodemailer`; Postgres 17 (Debian);
LibreOffice `*-nogui` packages (headless conversion to PDF).
Web: vanilla JS modules, `<dialog>`, drag-and-drop, XHR chunk upload. Android: Kotlin 2.1, Compose (BOM 2024.12),
OkHttp, kotlinx.serialization, Coil, Media3, WorkManager; AGP 8.7, compileSdk 35, minSdk 26.

## Commands
- Run published image: `cp .env.example .env` (edit) → `docker compose up -d`; UI on `http://localhost:${APP_PORT:-8080}`
- Build from source: `docker compose -f deploy/docker-compose.yml --env-file .env up -d --build`
- Update server: `docker compose pull && docker compose up -d`
- Logs: `docker logs -f cloudy` · Reset all data: `docker compose down -v`
- Type-check: `cd app && npm install && npm run typecheck`
- E2E test (stack running): `python3 tests/e2e.py http://localhost:8080 admin <password>`
- Android: `mobile/android/build-apk.sh` (needs JDK 17+, Android SDK 35). Releases (image + APK on GitHub) come
  from a `vX.Y.Z` tag; CI refuses it unless `app/package.json` and `versionName` are X.Y.Z.

## Conventions
- TypeScript must stay *erasable* (no enums/namespaces/parameter properties); imports use `.ts` extensions.
- DB access via `q`/`one`/`tx` in `db.ts`; raw SQL, parameterized only. New tables/columns go in `schema.sql` with `IF NOT EXISTS`.
- API errors: throw `HttpError(status, message)`; JSON API returns `{error}`. Mutating `/api` calls need header `X-Cloudy-Request: 1`.
- Access rules: read/edit on a node = owner or user/group share on it or an ancestor; changing/removing a node
  needs edit on its parent (`requireParentEdit`). Hidden nodes return 404, not 403.
- Storage: `APP_USER` (entrypoint drops to it via `setpriv`), `DATA_PATH` → `/data` (may be NFS), `DB_PATH` → `/var/lib/cloudy/db`
  (local disk). `ensureDirs()` probes writability and exits with a clear FATAL message. All of /data must be one filesystem.
- PostgreSQL major is pinned in the image (`PG_MAJOR` in entrypoint); a mismatch with `DB_PATH/PG_VERSION` stops startup — plan
  a dump/restore (or ship both versions + pg_upgrade) before bumping it.
- `TRUST_PROXY` gates all use of X-Forwarded-For/-Proto (`http.ts`) and -Host (`webauthn.ts`); keep it false when the port is exposed directly.
- Android authenticates with `POST /api/auth/token` → bearer token (an app password); keep `data/Models.kt` in sync with the JSON API.
- Releases: bump `app/package.json` version (server) and `versionCode`/`versionName` in `mobile/android/app/build.gradle.kts`;
  versionCode must always increase (server rejects lower ones). Tag `vX.Y.Z` to run the release workflow.
- Android signing key `mobile/android/cloudy-release.keystore` + `keystore.properties` are git-ignored and must be backed up;
  CI signs with the same key from repository secrets.
- Front end: build DOM with `h()` from `ui.js` (never innerHTML with data); each view exports `renderX(main, ctx)`.
- Passkeys: RP ID/origin from `PUBLIC_URL` or the request Host; only work over HTTPS/localhost. A passkey login skips TOTP (UV required).
- Live updates come from DB triggers, so new mutation paths need no extra code; new tables that affect file lists
  need their own trigger. Clients must tolerate duplicate/early events (they just reload).
- Office files are view-only (PDF previews); only text files are editable (web + Android, `PUT /api/nodes/:id/content`).
  Image ~1.3 GB (LibreOffice + fonts); PostgreSQL's LLVM JIT is deleted (`jit=off`).
- Android responsiveness: screens start from `ListCache` and refresh in the background; no disk IO on the UI thread;
  short NavHost transitions (`NAV_MS`); PDFs render one page at a time on demand.
- Email is optional: `sendMail` is a no-op without SMTP settings; never reveal whether an account exists.
- Android updates: the app never sends Cloudy credentials to GitHub (`Api.plainHttp`); update prefs are keyed by
  versionName (`skippedVersion`, `notifiedVersion`).
- Known limits: ZIP has no ZIP64 (<4 GB, <65k entries); recurring events edited as a whole series.

## Change Log
- 2026-10-20: 1.9.0 — Android *More → Connection check* (API, HTTPS, forwarded headers, WebDAV/CalDAV PROPFIND,
  .well-known, 8 MB upload, live updates; shareable report, DAV URLs to copy) + `GET /api/diagnostics`.
  Server/Android 1.9.0 (code 8).
- 2026-10-20: Added `docs/privacy-policy.md` (Google Play privacy policy; contact alhammadi.team@gmail.com).
- 2026-10-20: 1.8.0 — Contacts import from .vcf (vCard 2.1–4.0, QP/charsets) and .csv (Google, Outlook) with
  duplicate detection, and .vcf export; web (menu, Import button, drag & drop) and Android (picker, share sheet).
  Server/Android 1.8.0 (code 7).
- 2026-10-20: 1.7.0 — Email: admin SMTP settings + test, password reset by email (web; Android links to it), share
  notifications (per-user opt-out `notify_shares`), password-changed notice. Android: in-memory listing cache, fast
  transitions, IO off the UI thread, lazy PDF pages. fast-xml-parser 5.11.2 (security fixes). Server/Android 1.7.0 (code 6).
- 2026-10-20: 1.6.0 — Collabora removed; office files viewed read-only as PDF via built-in LibreOffice (`office.ts`).
  Android updates itself from GitHub releases (admin "Android app" section, `/api/app/android`, `app_releases` table,
  `publish-apk.sh` removed; `/download/android` redirects to GitHub). Text files editable/creatable on Android.
  Release workflow checks tag == versions and also uploads `Cloudy.apk`. Server 1.6.0, Android 1.6.0 (code 5).
- 2026-10-20: 1.5.1 — Live updates (DB triggers → LISTEN → SSE `/api/events`; web + Android reload changed views) and
  a Collabora editor (replaced in 1.6.0). Image moved Alpine → Debian 13; existing PG17 data dirs start unchanged.
  Tags v1.4.0/v1.5.0 point at the passkey-only commit.
- 2026-10-20: Passkey (WebAuthn) sign-in for the web app: passwordless + autofill, Settings → Passkeys (add/rename/remove,
  password-confirmed), admin "remove passkeys", `passkeys` table, `PUBLIC_URL` setting, `passkeys` count in user JSON.
- 2026-10-19: Renamed project FileCloud → **Cloudy** everywhere: Android package `io.cloudy.app`, image `ghcr.io/fahed-alhammadi/cloudy`,
  container/volumes/DB `cloudy`, header `X-Cloudy-Request`, cookies `cloudy_*`, keystore `cloudy-release.keystore` (alias `cloudy`,
  same key). Entrypoint migrates pre-rename databases (role/db `filecloud` → `cloudy`) once at startup.
- 2026-10-19: Two-factor authentication (TOTP + recovery codes, QR via `qrcode`) for web, Android and API; admin reset;
  DAV Basic accepts only app passwords when 2FA is on. Compose lists every setting under `environment:` with defaults
  (`.env` optional, overrides). Server 1.3.0, Android 1.3.0 (code 3).
- 2026-10-19: `.env` optional in both compose files (`env_file: required: false`); README zero-config `docker run` quick start.
- 2026-10-19: Removed server OTA (updater service, GitHub release check, `UPDATE_REPO`/`UPDATE_INTERVAL`); server updates are
  `docker compose pull`. OTA kept for the Android APK only.
- 2026-10-19: Swapped compose files: root `docker-compose.yml` = published image (+ inline updater); `deploy/docker-compose.yml`
  = build from source. Removed `scripts/auto-update.sh`; CI builds via the deploy file.
- 2026-10-19: Single all-in-one container (server + embedded PostgreSQL 17 via tini/entrypoint); `DB_PASSWORD` removed,
  `DB_PATH`/`DATABASE_URL` added, compose service renamed `cloudy` with `env_file: .env`. Existing volumes migrate as-is.
- 2026-10-19: Added `deploy/docker-compose.yml` (image-only deployment); root compose now passes `UPDATE_REPO` to the app.
- 2026-10-19: `.env` options `APP_USER` (run as uid:gid, default 1000:1000) and `DATA_PATH` (user-file storage, e.g. host NFS mount);
  startup writability check.
- 2026-10-19: CI fix — setup-android v3 failed installing legacy `tools`; now v4 with explicit SDK packages.
  All actions moved to Node 24 majors; runners pinned to ubuntu-24.04.
- 2026-10-19: Renamed project to Cloudy (package `io.cloudy.app`, new signing key, DB `cloudy`); removed iOS app.
  Over-the-air updates: server version/update check (`UPDATE_REPO`), `updater` compose service, Android APK
  publishing + in-app/daily update check. GitHub CI + release workflows. Server 1.1.0, Android 1.1.0 (code 2).
- 2026-10-19: Android app (files, previews, sharing, offline, photo backup, calendar, contacts); device-token auth.
- 2026-10-19: App port published directly for an external reverse proxy; `TRUST_PROXY` guards forwarded headers.
- 2026-10-19: Initial build — files, uploads, trash, versions, sharing, WebDAV, CalDAV/CardDAV, HTML5 UI, Compose stack.

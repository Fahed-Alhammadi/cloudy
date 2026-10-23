# Cloudy

## Overview
Self-hosted cloud for files, sharing, calendars and contacts: Node.js/TypeScript server + plain-HTML5 web app +
native Android app (Google Play only). Ships as ONE container (Node 24 server + embedded PostgreSQL 17 +
LibreOffice for office previews, started by `app/docker/entrypoint.sh`), configured from `.env`. TLS/reverse proxy is external.

## Structure
- `docker-compose.yml`, `.env.example` — service `cloudy` from `ghcr.io/fahed-alhammadi/cloudy:latest` (`env_file: .env`); update with `docker compose pull && docker compose up -d`; `.env` is git-ignored.
- `app/Dockerfile`, `app/docker/` — all-in-one Debian 13 image: `entrypoint.sh` (root → user setup, initdb, start postgres on
  the `/run/cloudy` socket, then node as APP_USER; supervises both), `pg_hba.conf`.
- `deploy/docker-compose.yml` — builds `app/` from source (`build: ../app`, `env_file: ../.env`); run from repo root with
  `-f deploy/docker-compose.yml --env-file .env`. Same project name/volumes as the root file. Keep the two in sync.
- `.github/workflows/` — `ci.yml` (typecheck, e2e against compose, Android debug build), `release.yml` (on `v*` tag: GHCR image, GitHub release notes, signed Play AAB → Play closed track).
- `docs/index.html` — GitHub Pages site (app info for Google Play, screenshots in `docs/screenshots/`, links to
  `privacy-policy.html`, which Jekyll renders from the .md). `docs/ARCHITECTURE.md` — design notes; `docs/logo.svg` — app logo (used in README); `docs/app-icon.svg` → `app-icon-512.png` — Google Play icon (launcher artwork, square, RGBA); `docs/play/` — Play
  store screenshots + feature graphic (`render.py`, headless Chrome); `docs/privacy-policy.md` — app privacy policy
  for Google Play (keep it in sync with what the Android app accesses). `tests/e2e.py` — end-to-end test (stdlib Python) against a running stack; `tests/i18n_check.py` — translations complete (CI).
- `app/src/server.ts` — entry: HTTP dispatch, CSRF guard, static app serving, hourly jobs (trash/upload/session expiry).
- `app/src/config.ts` — env config incl. `version` (APP_VERSION or package.json).
- `app/src/i18n.ts` — `LANGUAGES` (en, ar, de, fr, es), `pickLanguage` (account → Accept-Language → en), translated email texts.
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
  chunked uploads, trash, versions), `shares.ts` (shares + public links), `groupware.ts`.
- `app/public/` — HTML5 front end, no build step: `index.html`, `public.html` (`/s/<token>`), `js/*.js` (one module per view), `css/app.css`.
  `js/i18n.js` = `t('English text', {vars})` + `setLanguage`; translations in `public/i18n/<lang>.json` (English keys).
- `mobile/android/` — Kotlin/Compose app `io.cloudy.app`: `data/` (Api, Session, Offline, ConnectionCheck = proxy/DAV diagnostics, ListCache = in-memory listings, Live = SSE + Office,
  Lang = app language ↔ account, AppLock = biometric lock), `work/Workers.kt` (photo backup, offline sync), `ui/` screens (PreviewScreen edits text files;
  Browse.kt = grid/sort, search filters, ML Kit document scanner; Security.kt = lock screen, language dialog, Devices). Strings in `res/values*/strings.xml`. No flavors.
  `build-apk.sh` → `mobile/dist/` Play AAB + APK for adb testing.

## Tech Stack
Server: Node 24 running TypeScript directly (type stripping), `pg`, `ical.js`, `fast-xml-parser`, `nodemailer`; Postgres 17 (Debian);
LibreOffice `*-nogui` packages (headless conversion to PDF).
Web: vanilla JS modules, `<dialog>`, drag-and-drop, XHR chunk upload. Android: Kotlin 2.1, Compose (BOM 2024.12),
OkHttp, kotlinx.serialization, Coil, Media3, WorkManager; AGP 8.9, compileSdk/targetSdk 36, minSdk 26.

## Commands
- Run published image: `cp .env.example .env` (edit) → `docker compose up -d`; UI on `http://localhost:${APP_PORT:-8080}`
- Build from source: `docker compose -f deploy/docker-compose.yml --env-file .env up -d --build`
- Update server: `docker compose pull && docker compose up -d`
- Logs: `docker logs -f cloudy` · Reset all data: `docker compose down -v`
- Type-check: `cd app && npm install && npm run typecheck`
- E2E test (stack running): `python3 tests/e2e.py http://localhost:8080 admin <password>` · Translations: `python3 tests/i18n_check.py`
- Android: `mobile/android/build-apk.sh` (needs JDK 17+, Android SDK 36) → Play AAB + test APK. Releases (image + Play AAB) come
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
- `TRUST_PROXY` gates all use of X-Forwarded-For/-Proto/-Host (`http.ts`); keep it false when the port is exposed directly.
  `clientIp()` walks X-Forwarded-For + socket from the right, skipping local (proxy) addresses, so chains of proxies work.
- Android authenticates with `POST /api/auth/token` → bearer token (an app password); keep `data/Models.kt` in sync with the JSON API.
- Releases: bump `app/package.json` version (server) and `versionCode`/`versionName` in `mobile/android/app/build.gradle.kts`;
  versionCode must always increase (server rejects lower ones). Tag `vX.Y.Z` to run the release workflow.
- Android signing key `mobile/android/cloudy-release.keystore` + `keystore.properties` are git-ignored and must be backed up;
  CI signs with the same key from repository secrets.
- Front end: build DOM with `h()` from `ui.js` (never innerHTML with data); each view exports `renderX(main, ctx)`.
- i18n: every user-visible text goes through `t()` (web, dynamic parts as `{name}`) or `R.string` (Android); add the key to all
  `public/i18n/*.json` / `values-*/strings.xml` (`tests/i18n_check.py` fails otherwise). Language lists live in i18n.ts, i18n.js,
  `locales_config.xml`, `Lang.kt` — keep identical. `users.language` NULL = browser/phone; emails use the recipient's. Arabic is RTL:
  CSS uses logical properties (`inset-inline-*`, `margin-inline-*`, `text-align: start`); Compose uses AutoMirrored icons.
  Play bundle keeps all languages (`bundle.language.enableSplit = false`) so the in-app switch works. ConnectionCheck texts stay English.
- Passkeys: RP ID/origin from `PUBLIC_URL` or the request Host; only work over HTTPS/localhost. A passkey login skips TOTP (UV required).
- Live updates come from DB triggers, so new mutation paths need no extra code; new tables that affect file lists
  need their own trigger. Clients must tolerate duplicate/early events (they just reload).
- Office files are view-only (PDF previews); only text files are editable (web + Android, `PUT /api/nodes/:id/content`).
  Image ~1.3 GB (LibreOffice + fonts); PostgreSQL's LLVM JIT is deleted (`jit=off`).
- Android insets: the app is edge-to-edge (`enableEdgeToEdge`); keyboard-facing screens need `imePadding()`, floating
  buttons `Modifier.sideInsets()`. Test landscape with 3-button navigation.
- Android state: anything the user types must survive rotation/process death — `rememberSaveable` (+ `jsonSaver` for
  models); restored editors wait for their data (calendars/address book) before showing.
- Android responsiveness (Play ANRs): screens start from `ListCache` and refresh in the background; no disk IO on the UI thread
  (file copies, `Offline` changes, `usedBytes`, content queries → `Dispatchers.IO`; `Jobs.schedule` is safe anywhere); debug builds
  log violations via StrictMode (`adb logcat | grep StrictMode`); short NavHost transitions (`NAV_MS`); PDFs render one page at a time.
  Debug builds are `io.cloudy.app.debug` (install next to the Play version).
- Email is optional: `sendMail` is a no-op without SMTP settings; never reveal whether an account exists.
- Android ships only through Google Play: no self-updating/APK-installing code, no REQUEST_INSTALL_PACKAGES.
  Play App Signing: Google holds the app key, `cloudy-release.keystore` is the upload key.
- Known limits: ZIP has no ZIP64 (<4 GB, <65k entries); recurring events edited as a whole series.

## Change Log
- 2026-10-23: 1.10.0 (code 15) — Multi-language: English, Arabic (RTL), German, French, Spanish on web, Android and emails;
  account language (`users.language`, `PATCH /api/me {language}`) picked in Settings → Profile / More → Language (Android 13+
  per-app language). Android (OpenCloud-inspired): document scanner → PDF, grid view + sort, search type filters, link expiry +
  "allow upload", app lock (biometric/screen lock), Devices screen (`/api/me/app-passwords` marks `current`). Bidi-safe sizes.
  ANR fixes: offline pin/unpin/sync, uploads, Save to device, Send/Open with, text load and storage size off the UI thread.
- 2026-10-22: 1.9.6 (code 14) — Google Play only: removed the GitHub self-updater (`github`/`play` flavors, AppUpdater, UpdateUi,
  UpdateWorker, `Api.plainHttp`, install/notification permissions), `/download/android` + `APP_REPO`, the login-page
  and Settings download links; release workflow no longer attaches APKs; docs/privacy policy updated.
- 2026-10-21: 1.9.5 (code 13) — edge-to-edge (Play notice): dropped deprecated statusBarColor/navigationBarColor from the
  theme (enableEdgeToEdge does it), text editor `imePadding` above the keyboard, FABs clear side nav bars/cutouts (`sideInsets`).
- 2026-10-21: 1.9.4 (code 12) — Android QA fixes: "Discard changes?" before leaving the text editor; search field focused
  on open; *Manage sharing* (list/stop links and user shares, `SharesDialog`); event end follows the start, end-before-start
  blocks Save and a rejected save keeps the editor open; contact birthday validated + date picker.
- 2026-10-21: 1.9.3 (code 11) — Android: *Send to…* shares the file itself with other apps (WhatsApp, Telegram…; `sendToApp`
  in FilesScreen); "available offline" files open without network (`Offline.savedNode`, node details in state.json);
  editor drafts, event and contact forms survive rotation (`rememberSaveable`, `jsonSaver` in Common.kt); Connection
  check shows a local client IP on Wi-Fi as INFO (NAT loopback), not a warning.
- 2026-10-21: Play Store graphics: `docs/app-icon-512.png`, `docs/play/` (render.py; Play rejects the raw 1080×2400 captures).
- 2026-10-21: 1.9.2 (code 10) — targetSdk/compileSdk 36 (Play requirement), AGP 8.9.1. Android `github`/`play` flavors; Play AAB without the self-updater; release workflow builds it, keeps it
  as an artifact and uploads to Play's closed track when `PLAY_SERVICE_ACCOUNT_JSON` is set.
- 2026-10-21: Entrypoint aliases missing UTF-8 locales from old Alpine data dirs (e.g. `en_US.utf8`) to `C.utf8` —
  they stopped PostgreSQL starting on the Debian image; musl collation = code point, so indexes stay valid.
  Store screenshots in `docs/screenshots/` (emulator, demo user).
- 2026-10-21: `docs/index.html` app page for GitHub Pages / Google Play listing (features, screenshot gallery, privacy-policy link).
- 2026-10-20: 1.9.1 — Real client IP behind several proxies (rightmost public address in X-Forwarded-For; all-local →
  leftmost), `/api/diagnostics` shows the raw chain, Connection check tells "TRUST_PROXY off" from "IP lost before the proxy".
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
- 2026-10-19: Initial build (files, sharing, WebDAV/CalDAV/CardDAV, HTML5 UI) and Android app; renamed FileCloud → Cloudy
  (entrypoint migrates old `filecloud` DBs); single all-in-one container with embedded PostgreSQL 17; `APP_USER`/`DATA_PATH`;
  `TRUST_PROXY`; TOTP 2FA (DAV Basic then needs app passwords); GitHub CI + release workflows (Node 24 actions, ubuntu-24.04).

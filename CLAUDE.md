# Cloudy

## Overview
Self-hosted cloud for files, sharing, calendars and contacts: Node.js/TypeScript server + plain-HTML5 web app +
native Android app (Google Play only) + native iPhone app (App Store). Ships as ONE container (Node 24 server + embedded PostgreSQL 17 +
LibreOffice for office previews, started by `server/docker/entrypoint.sh`), configured from `.env`. TLS/reverse proxy is external.

## Structure
- `docker-compose.yml`, `.env.example` — service `cloudy` from `ghcr.io/fahed-alhammadi/cloudy:latest` (`env_file: .env`); update with `docker compose pull && docker compose up -d`; `.env` is git-ignored.
- `server/Dockerfile`, `server/docker/` — all-in-one Debian 13 image: `entrypoint.sh` (root → user setup, initdb, start postgres on
  the `/run/cloudy` socket, then node as APP_USER; supervises both), `pg_hba.conf`.
- `deploy/docker-compose.yml` — builds `server/` from source (`build: ../server`, `env_file: ../.env`); run from repo root with
  `-f deploy/docker-compose.yml --env-file .env`. Same project name/volumes as the root file. Keep the two in sync.
- Monorepo: three products, each with its own folder, version, workflow and tag — `server/` (container → GHCR, `server-vX.Y.Z`),
  `android/` (→ Google Play, `android-vX.Y.Z`), `ios/` (→ App Store Connect/TestFlight, `ios-vX.Y.Z`). Shared: `docs/`, `tests/`, `deploy/`, `scripts/`.
- `.github/workflows/` — `server.yml` / `android.yml` / `ios.yml` run only when their folder changes (path filters): test/build on
  every push and PR; on main, if the product's version has no tag yet, publish it and tag it (`scripts/github-release.sh`: tag +
  GitHub release with that folder's commits). `translations.yml` runs `i18n_check.py` when any strings change.
- `scripts/version.py` — `status` / `bump <product> X.Y.Z` / `get` / `build` / `released`: the one place that reads and writes versions.
  Release notes: `android/whatsnew/whatsnew-en-US` (Play upload) and `ios/whatsnew/en-US.txt` (`scripts/appstore-whatsnew.mjs`:
  App Store Connect API → TestFlight "What to Test" + App Store "What's New"); update them with each bump.
- `docs/index.html` — GitHub Pages site (app info for Google Play, screenshots in `docs/screenshots/`, links to
  `privacy-policy.html`, which Jekyll renders from the .md). `docs/ARCHITECTURE.md` — design notes; `docs/logo.svg` — app logo (used in README); `docs/app-icon.svg` → `app-icon-512.png` — Google Play icon (launcher artwork, square, RGBA); `docs/play/` — Play
  store screenshots + feature graphic (`render.py`, headless Chrome); `docs/privacy-policy.md` — app privacy policy
  for both apps (keep it in sync with what the apps access). `tests/e2e.py` — end-to-end test (stdlib Python) against a running stack; `tests/i18n_check.py` — translations complete (CI).
- `server/src/server.ts` — entry: HTTP dispatch, CSRF guard, static app serving, hourly jobs (trash/upload/session expiry).
- `server/src/config.ts` — env config incl. `version` (APP_VERSION or package.json).
- `server/src/i18n.ts` — `LANGUAGES` (en, ar, de, fr, es), `pickLanguage` (account → Accept-Language → en), translated email texts.
- `server/src/schema.sql` — whole DB schema, applied idempotently at every start (no migration tool).
- `server/src/files.ts` — file model: node tree in Postgres, immutable blobs on disk, size/etag propagation,
  trash, versions, quota, share-based access checks. All file mutations go through here.
- `server/src/download.ts` — Range downloads (inline-safe types only) and streaming store-only ZIP.
- `server/src/auth.ts` / `users.ts` — scrypt passwords, cookie sessions, bearer device tokens, Basic auth + app passwords, rate limit, user lifecycle.
- `server/src/totp.ts` — TOTP (RFC 6238) + recovery codes; 2FA routes in `api/account.ts` (`/api/me/2fa/*`), enforced in `auth.login()`.
- `server/src/webauthn.ts` — passkeys (CBOR/COSE parsing, challenges, signature checks, no deps); routes in `api/passkeys.ts`
  (`/api/passkeys/login*`, `/api/me/passkeys*`), login in `auth.loginWithPasskey()`; browser glue `public/js/passkey.js`.
- `server/src/events.ts` — live updates: LISTEN on `cloudy_changes` (triggers in schema.sql) → SSE `GET /api/events` `{folders, shares}`.
- `server/src/office.ts` — read-only office previews: `GET /api/nodes/:id/preview` = LibreOffice → PDF (queued, cached in
  `DATA_DIR/cache/previews/<id>-<etag>.pdf`), `GET /api/office` lists extensions. Web: `public/js/office.js` + viewer.
- `server/src/mail.ts` — SMTP settings (`settings.smtp`, admin `/api/admin/mail*`), `sendMail` (background) / `sendMailNow`,
  password reset (`/api/auth/forgot|reset|options`, `password_resets`), share + password-changed notifications.
- `server/src/contacts-import.ts` — parses uploaded contacts: vCard 2.1–4.0 (normalized to 3.0/4.0) and CSV (Google,
  Outlook, simple); routes `/api/addressbooks/:id/import|export` in `api/groupware.ts` (UID update, duplicate skip).
- `server/src/groupware.ts` — calendar/address-book store shared by DAV and JSON API (ical.js parsing, sync change log).
- `server/src/dav/` — `server.ts` WebDAV (files) + CalDAV/CardDAV under `/dav`; `xml.ts` namespace-aware XML + multistatus.
- `server/src/api/` — JSON API: `account.ts` (login, device tokens, me, app passwords, admin), `files.ts` (nodes,
  chunked uploads, trash, versions), `shares.ts` (shares + public links), `groupware.ts`.
- `server/public/` — HTML5 front end, no build step: `index.html`, `public.html` (`/s/<token>`), `js/*.js` (one module per view), `css/app.css`.
  `js/i18n.js` = `t('English text', {vars})` + `setLanguage`; translations in `public/i18n/<lang>.json` (English keys).
- `android/` — Kotlin/Compose app `io.cloudy.app`: `data/` (Api, Session, Offline, ConnectionCheck = proxy/DAV diagnostics, ListCache = in-memory listings, Live = SSE + Office,
  Lang = app language ↔ account, AppLock = biometric lock), `work/Workers.kt` (photo backup, offline sync), `ui/` screens (PreviewScreen edits text files;
  Browse.kt = grid/sort, search filters, ML Kit document scanner; Security.kt = lock screen, language dialog, Devices). Strings in `res/values*/strings.xml`. No flavors.
  `build-apk.sh` → `android/dist/` Play AAB + APK for adb testing. `android/README.md` — build/release notes.
- `ios/` — SwiftUI app `io.cloudy.app` (iOS 17+, no deps), project from `project.yml` (XcodeGen; `.xcodeproj` git-ignored).
  `Cloudy/Data/` (API = URLSession client incl. chunked upload + SSE, Session = Keychain token, Live, Uploads, Thumbnails = grid previews),
  `Cloudy/UI/` (FilesView, FileActions = menus/Quick Look/share sheet, ShareSheetView, ListsView = favorites/recent/shared/links/trash,
  TextFileView, Pickers = folder picker/photos/VisionKit scanner, CalendarView, ContactsView, MoreView), `Cloudy/Resources/`
  (`Localizable.xcstrings`, `InfoPlist.xcstrings`, `PrivacyInfo.xcprivacy`, assets). README has the App Store checklist.
  `ios/appstore/` — App Store screenshots (`raw/` simulator captures → `render.py` → `en-US/`); Debug-only `UI/Screenshots.swift` opens screens from env.

## Tech Stack
Server: Node 24 running TypeScript directly (type stripping), `pg`, `ical.js`, `fast-xml-parser`, `nodemailer`; Postgres 17 (Debian);
LibreOffice `*-nogui` packages (headless conversion to PDF).
Web: vanilla JS modules, `<dialog>`, drag-and-drop, XHR chunk upload. Android: Kotlin 2.1, Compose (BOM 2024.12),
OkHttp, kotlinx.serialization, Coil, Media3, WorkManager; AGP 8.9, compileSdk/targetSdk 36, minSdk 26.
iOS: Swift 5 mode, SwiftUI + Observation, Quick Look, PhotosUI, VisionKit, LocalAuthentication; Xcode 16+, XcodeGen.

## Commands
- Run published image: `cp .env.example .env` (edit) → `docker compose up -d`; UI on `http://localhost:${APP_PORT:-8080}`
- Build from source: `docker compose -f deploy/docker-compose.yml --env-file .env up -d --build`
- Update server: `docker compose pull && docker compose up -d`
- Logs: `docker logs -f cloudy` · Reset all data: `docker compose down -v`
- Type-check: `cd server && npm install && npm run typecheck`
- Versions: `scripts/version.py status` (after `git fetch --tags`) · release = `scripts/version.py bump <server|android|ios> X.Y.Z`,
  commit, push to main → that product's workflow publishes it. No manual tags.
- E2E test (stack running): `python3 tests/e2e.py http://localhost:8080 admin <password>` · Translations: `python3 tests/i18n_check.py`
- Android: `android/build-apk.sh` (needs JDK 17+, Android SDK 36) → `android/dist/` Play AAB + test APK.
- iOS: `ios/build.sh` (simulator build) · `DEVELOPMENT_TEAM=<id> ios/build.sh archive|upload` → `ios/dist/` / TestFlight (`upload`
  uses `ASC_KEY_ID/ASC_ISSUER_ID/ASC_KEY_PATH` if set). Debug-only simulator login: `SIMCTL_CHILD_CLOUDY_SERVER/_TOKEN/_TAB` (ios/README.md).

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
- Releases: each product is versioned and released on its own (`scripts/version.py bump`; Android versionCode / iOS build
  number go up by one automatically and must never go down). Releasing = version without a `<product>-v` tag reaching main;
  a change without a bump only runs CI. Old `vX.Y.Z` tags (≤1.10.0) count as released for server and Android. Store releases
  stop at Play closed testing / TestFlight; production promotion and App Review submission stay manual.
- **"What's new" — always:** every user-visible feature or fix in an app, and every Android/iOS version bump, must update
  the release notes in the same commit: `android/whatsnew/whatsnew-en-US` (Play, ≤ 500 characters) and
  `ios/whatsnew/en-US.txt` (TestFlight/App Store). English, short "• " bullets describing what's new for users in that
  version (replace the previous version's text, don't append). Never bump an app version without checking them.
- Android signing key `android/cloudy-release.keystore` + `keystore.properties` are git-ignored and must be backed up;
  CI signs with the same key from repository secrets.
- Front end: build DOM with `h()` from `ui.js` (never innerHTML with data); each view exports `renderX(main, ctx)`.
- i18n: every user-visible text goes through `t()` (web, dynamic parts as `{name}`) or `R.string` (Android); add the key to all
  `public/i18n/*.json` / `values-*/strings.xml` (`tests/i18n_check.py` fails otherwise). Language lists live in i18n.ts, i18n.js,
  `locales_config.xml`, `Lang.kt`, iOS `Info.plist` `CFBundleLocalizations` — keep identical. iOS strings: English text as key
  (`Text("…")`, `String(localized:)`) in `Localizable.xcstrings`; Xcode adds new keys on build, translate them in all languages. `users.language` NULL = browser/phone; emails use the recipient's. Arabic is RTL:
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
- iOS / App Store: keep `Data/Models.swift` in sync with the JSON API too. No sign-up, payments, tracking or analytics
  (privacy label "no data collected", `PrivacyInfo.xcprivacy`); ATS allows http only on local networks; permission texts only
  for features that use them; no mention of Android/Google Play in the iOS UI. Privacy policy covers both apps (section "iPhone app").
- File menus/multi-select work the same on web, Android and iOS: long-press (touch) / right-click / ⋯ opens the file's
  actions, whose first entry "Select" starts multi-select; then taps toggle files and a bar acts on all of them
  (send/download, move or copy, favorite, delete; one API call per file). Keep the three in step when adding actions.
- Known limits: ZIP has no ZIP64 (<4 GB, <65k entries); recurring events edited as a whole series.

## Change Log
- 2026-10-25: Store release notes from the repo (English): Play `whatsNewDirectory`, iOS notes via App Store Connect API.
- 2026-10-25: Multi-select + long-press menus on web, Android (`Selection.kt`, list long-press → sheet) and iOS (List edit
  mode / grid check circles); web long-press on touch, checkboxes on phones while selecting. Tested in simulator, emulator, Chrome.
- 2026-10-25: iOS Files tab like Android: only files (shortcuts moved to More), ⋯ per row, list/grid + `Thumbnails`, round +,
  search type filters. App Store assets in `ios/appstore/` (screenshots, banners, preview video; Debug-only `CLOUDY_*` hooks).
- 2026-10-25: Monorepo split (`server/`, `android/`, `ios/`), own version + tag per product (`scripts/version.py`), path-filtered
  workflows publish on version bump (GHCR / Play closed testing / TestFlight via App Store Connect API key).
- 2026-10-24: iOS app 1.10.0 in `ios/`: files (Quick Look, uploads, VisionKit scanner, sharing, text editor), calendar,
  contacts, devices, app lock, live updates, 5 languages; privacy policy has an iPhone section.
- 2026-10-23: 1.10.0 (code 15) — Multi-language: English, Arabic (RTL), German, French, Spanish on web, Android and emails;
  account language (`users.language`, `PATCH /api/me {language}`) picked in Settings → Profile / More → Language (Android 13+
  per-app language). Android (OpenCloud-inspired): document scanner → PDF, grid view + sort, search type filters, link expiry +
  "allow upload", app lock (biometric/screen lock), Devices screen (`/api/me/app-passwords` marks `current`). Bidi-safe sizes.
  ANR fixes: offline pin/unpin/sync, uploads, Save to device, Send/Open with, text load and storage size off the UI thread.
- 2026-10-22: 1.9.6 (code 14) — Google Play only: removed the GitHub self-updater (`github`/`play` flavors, AppUpdater, UpdateUi,
  UpdateWorker, `Api.plainHttp`, install/notification permissions), `/download/android` + `APP_REPO`, the login-page
  and Settings download links; release workflow no longer attaches APKs; docs/privacy policy updated.
- 2026-10-21: 1.9.2–1.9.5 — edge-to-edge fixes (`imePadding`, `sideInsets`), targetSdk 36/AGP 8.9.1, Play graphics (`docs/play/`), `docs/index.html`, Send to other apps,
  offline files open offline, forms survive rotation, Android QA fixes (discard dialog, Manage sharing, event validation);
  entrypoint aliases old Alpine UTF-8 locales to `C.utf8` so PostgreSQL starts on Debian.
- 2026-10-20: 1.5.1–1.9.1 — live updates (triggers → SSE), Debian image, built-in LibreOffice previews (Collabora removed),
  email (SMTP, password reset, share notices), contacts import/export, Connection check + `/api/diagnostics`, real client
  IP behind proxy chains, passkeys, privacy policy.
- 2026-10-19: Initial build (files, sharing, WebDAV/CalDAV/CardDAV, HTML5 UI) and Android app; renamed FileCloud → Cloudy
  (entrypoint migrates old `filecloud` DBs); single all-in-one container with embedded PostgreSQL 17; `APP_USER`/`DATA_PATH`;
  `TRUST_PROXY`; TOTP 2FA (DAV Basic then needs app passwords); GitHub CI + release workflows (Node 24 actions, ubuntu-24.04).

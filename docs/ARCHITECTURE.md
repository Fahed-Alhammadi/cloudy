# Cloudy architecture

Cloudy is a self-hosted cloud for files, sharing, calendars and contacts. It has three parts:
- one container running the Node.js server and its own PostgreSQL database;
- a browser app written in plain HTML5;
- a native Android app.

This document explains how the pieces fit together and why they are built the way they are.

## 1. Components

```
                 ┌───────────────── Cloudy server (Node 24, TypeScript) ─────────────────┐
browser ───────▶ │  /            static HTML5 app (public/)                                   │
Android app ───▶ │  /api/*       JSON API (session cookie or device token)                    │
                 │  /api/public  public share links (token + optional password)               │
WebDAV / CalDAV  │  /dav/*       WebDAV files, CalDAV calendars, CardDAV address books         │
/ CardDAV apps ─▶│  /download/android   latest Android APK (over-the-air updates)              │
                 └─────────────┬──────────────────────────────────┬──────────────────────────┘
                               │                                  │
                         PostgreSQL 17                       /data volume
                   (metadata, shares, users,          blobs/  file contents (immutable)
                    calendars, contacts, releases)    uploads/ resumable uploads in progress
                                                      releases/ published APKs
```

**Container layout.**
- `tini` runs as process 1 and starts `cloudy-entrypoint`.
- The entrypoint, still as root, does the setup:
  - creates a named user for `APP_USER` (uid:gid);
  - fixes ownership of the database folder;
  - runs `initdb` the first time.
- It then drops to `APP_USER` and starts PostgreSQL 17, which listens only on the Unix socket
  `/run/cloudy`, with trust authentication and no TCP port.
- Once the database answers, it starts the Node server.
- If either process exits, the other is stopped and the container exits, so Docker's restart policy restarts
  both together.
- `docker stop` shuts both down cleanly.
- Setting `DATABASE_URL` skips the embedded database entirely.

TLS is provided by your own reverse proxy in front of the app port (see README). The server runs TypeScript
directly through Node's type stripping, so there is no build step. The web app is plain ES modules served as
static files.

## 2. The file model

```
nodes (PostgreSQL)                      DATA_DIR/blobs/ab/ab12…uuid
┌──────────────────────────────┐        ┌──────────────────────────┐
│ id, owner, parent_id, name   │  blob  │  file bytes, written     │
│ is_dir, size, mime, etag     │ ─────▶ │  once, never modified    │
│ mtime, deleted_at, …         │        └──────────────────────────┘
└──────────────────────────────┘
versions(node_id, blob, size, mtime)   ← the previous blob on each overwrite
```

- **The folder tree lives only in the database.** Rename and move change one row and never touch the disk.
- **File contents are immutable blobs.** Saving a file writes a new blob, and the old one becomes a version at
  no copying cost. Each file keeps `MAX_VERSIONS` versions.
- **Size and etag propagate up the tree.** Every change updates the size and etag of all parent folders, inside
  the same transaction. Clients detect changes by comparing one folder's etag.
- **Trash** marks a whole subtree with one `deleted_at` timestamp and remembers the original path. Restore brings
  back exactly that subtree. Items trashed earlier inside it stay in the trash. Trash is emptied after
  `TRASH_RETENTION_DAYS`.
- **Quota** counts live files, trash and versions.
- **Uploads** are resumable. The client opens an upload session, sends 8 MB chunks with an `Upload-Offset`
  header, and resumes from the server's offset after a network error.

## 3. Sharing and permissions

One `shares` table holds three kinds of share:
- **user** and **group** shares, each either read-only or editable;
- **public links**, with an optional password, expiry date and "allow upload".

Access rules:
- A user can **read** a node if they own it, or if it or one of its parent folders is shared with them or their
  group.
- **Changing or deleting** a node requires edit rights on its parent folder.
- A link only ever reaches the shared subtree. This is checked through the folder chain on every request.
- A link password unlocks a signed cookie (HMAC with a server secret).
- Nodes a user can't see return 404, not 403, so names of other people's files never leak.

## 4. Protocols

| Path | Purpose |
|---|---|
| `/dav/files/<user>/…` | WebDAV: PROPFIND, PROPPATCH, GET, PUT, MKCOL, DELETE, MOVE, COPY, LOCK/UNLOCK. Locks are accepted but not enforced, which macOS Finder and Windows need to mount read-write. |
| `/dav/calendars/<user>/…` | CalDAV: MKCALENDAR, calendar-query (time ranges), calendar-multiget, sync-collection |
| `/dav/addressbooks/<user>/…` | CardDAV: extended MKCOL, addressbook-query, addressbook-multiget, sync-collection |
| `/.well-known/caldav`, `/.well-known/carddav` | Auto-discovery redirects |

Calendars and contacts are stored as raw iCalendar/vCard text and parsed with `ical.js`. The browser and the
Android app use a JSON API that converts to and from these formats, and expands repeating events.

Change tracking for sync: a change log (`dav_changes`) plus a counter per collection lets clients fetch only
what changed since their last sync.

## 5. Authentication

| Client | How it signs in |
|---|---|
| Browser | Password or passkey → session cookie (HttpOnly, SameSite=Lax, Secure behind HTTPS) |
| Android app | `POST /api/auth/token` → device token, sent as `Authorization: Bearer …` |
| WebDAV / CalDAV / CardDAV apps | HTTP Basic with the account password or an app password |

- **Two-factor authentication** uses TOTP (RFC 6238: SHA-1, 6 digits, 30 s, ±1 step), implemented in `totp.ts`.
  - Setup keeps the secret pending until a valid code confirms it. Then 10 single-use recovery codes are issued,
    stored as SHA-256 hashes.
  - At login, a missing code returns `401 {twoFactor: true}`, so clients show the code field.
  - The last used time step is stored, so a code can't be replayed.
  - With 2FA on, HTTP Basic accepts only app passwords, never the account password.
- **Passkeys** (WebAuthn) are implemented in `webauthn.ts` with `node:crypto` only (minimal CBOR/COSE; ES256, EdDSA,
  RS256). Attestation is `none`, so the authenticator model is not verified.
  - Discoverable credentials with user verification required, so sign-in needs no username and skips TOTP.
  - RP ID = hostname of `PUBLIC_URL`, else of `Host` (`X-Forwarded-Host` with `TRUST_PROXY`); client origin must match.
  - Challenges are single-use, in memory, 5 minutes. A signature counter that fails to increase is rejected.
  - Registering one needs the account password; `user.id` is an HMAC of the username, not the username.
- Device tokens are app passwords. Users see them, and can revoke them, under *Settings → App passwords*.
- Passwords are hashed with scrypt. Tokens are stored as SHA-256 hashes.
- Failed sign-ins are rate-limited per client IP. Behind a proxy, `TRUST_PROXY=true` makes the real client IP
  visible.
- Every state-changing `/api` call must carry the `X-Cloudy-Request: 1` header. A cross-site form can't send it, so
  this blocks CSRF.

## 6. Updates

**Server.** Each `v*` tag publishes `ghcr.io/fahed-alhammadi/cloudy:latest`. Run
`docker compose pull && docker compose up -d` to update.
- `/status` reports the running version.
- The database and `/data` live in volumes, so they survive updates. The schema is applied idempotently at every
  start.

**Android app (from GitHub releases).**
1. A `v*` tag builds the signed APK in CI and attaches `Cloudy-<version>.apk` and `Cloudy.apk` to the GitHub
   release. The workflow fails if the tag doesn't match `app/package.json` or the app's `versionName`.
2. The app asks `api.github.com/repos/<UPDATE_REPO>/releases/latest` on launch and daily (WorkManager, with a
   notification) and offers the update when the tag is newer than its `versionName`.
3. It downloads the APK with an HTTP client that carries no Cloudy credentials, checks the SHA-256 GitHub
   reports for the asset, and opens the system installer.
4. Android itself refuses an APK signed with a different key, so only builds from the original signing key can
   update an install.
5. `/download/android` redirects to `github.com/<APP_REPO>/releases/latest/download/Cloudy.apk` (login page link).

## 7. Security notes

- Downloads: only images (except SVG), audio, video, PDF and text are ever served inline. Text is always sent as
  `text/plain`, with `nosniff` and a sandbox CSP. Uploaded HTML or SVG can't run on the app's origin.
- The web app runs under a strict Content Security Policy (`default-src 'self'`, no inline scripts).
- Uploaded APKs must be ZIP archives, and their version code must increase.

## 8. Known limits

- Folder ZIP downloads have no ZIP64 support, so they must stay under 4 GB and 65,535 entries.
- Repeating events are edited as a whole series.
- No server-side encryption, external storage, thumbnails, full-text search, or calendar
  sharing yet. Passkeys are web-only (no Android Credential Manager sign-in).

## 9. Live updates

Triggers on `nodes` and `shares` (`schema.sql`) call `pg_notify('cloudy_changes', …)`; Postgres delivers
notifications only after commit and merges identical ones in a transaction, so trashing a big folder sends
one per affected folder. `events.ts` keeps one `LISTEN` connection (reconnects), batches for 250 ms, works out
who can see each changed folder (owner + user/group shares on it or an ancestor) and pushes
`event: change` / `{folders, shares}` over Server-Sent Events at `GET /api/events` (pings every 25 s,
`X-Accel-Buffering: no`). Because the triggers sit in the database, every path — JSON API, WebDAV, background
jobs, office saves — is covered. Clients reload the view only when it shows a changed folder, and the web app
waits while a dialog, menu or selection is open. Android collects the stream while the app is in the foreground.

## 10. Office previews (LibreOffice)

The image includes LibreOffice without GUI (`libreoffice-*-nogui`). `GET /api/nodes/:id/preview` (read access)
converts an office file to PDF with `soffice --headless --convert-to pdf` and streams it; browsers show it in their
PDF viewer, Android renders it with `PdfRenderer`. `GET /api/office` lists the previewable extensions.
- Results are cached as `DATA_DIR/cache/previews/<id>-<etag>.pdf`: a changed file gets a new etag and is converted
  again, older PDFs of that file are deleted, and an hourly job removes previews unused for 30 days.
- One conversion runs at a time (queue), identical requests share it, 120 s timeout, 100 MB size limit. The blob is
  symlinked with its real extension so LibreOffice picks the right import filter; the LibreOffice profile lives in
  `/tmp` (not on a possibly-NFS `DATA_DIR`).
- Previews are read-only. Editing is limited to text files (web viewer and Android), saved through
  `PUT /api/nodes/:id/content`, which keeps the previous content as a version.

## 11. Email

`mail.ts` sends through the SMTP server configured in *Administration → Email* (stored in `settings` key `smtp`;
the password is never returned by the API) using `nodemailer`. Messages have a plain-text and a simple HTML part
and are sent in the background — failures are logged, never shown to the user who triggered them.
- **Password reset**: `POST /api/auth/forgot {login}` (username or email, case-insensitive) always answers 204 and
  is rate-limited per IP (5/h) and per account (3/h). It stores the SHA-256 of a random token in
  `password_resets` (60 min, one per user) and mails `<origin>/#/reset/<token>`. `POST /api/auth/reset
  {token, password}` consumes the token, sets the password, deletes all sessions and sends a notice.
  `GET /api/auth/options` tells the login page whether to offer it.
- **Notifications**: new user/group shares (recipients with an email and `notify_shares`, not the sharer) and
  password changes. Links use `PUBLIC_URL` or the origin of the request that caused the email.

## 12. Contacts import and export

`POST /api/addressbooks/:id/import` takes the raw file (20 MB, 20,000 contacts at most); `contacts-import.ts`
detects the format and encoding (UTF-8, UTF-16 with BOM, else Windows-1252).
- **vCard**: cards are split, unfolded and normalized to 3.0 (4.0 stays 4.0): vCard 2.1 quoted-printable values
  are decoded with their CHARSET, bare parameters become `TYPE=`, `item1.` groups are dropped, 2.1 text is escaped
  for 3.0, cards without FN get one from N/ORG/EMAIL/TEL, and every card gets a UID. ical.js validates the result.
- **CSV**: RFC 4180 with the delimiter guessed from the header (`,` `;` tab); columns are mapped by name (Google's
  `E-mail 1 - Value`, `::: `-separated multi-values, Outlook's `Home Street`… parts, plain `Name/Email/Phone`).
- **Duplicates**: a card whose UID exists replaces that contact; otherwise a contact with the same name and a shared
  email or phone (last 9 digits) is skipped. The response counts `imported`, `updated`, `duplicates`, `skipped`.
- `GET /api/addressbooks/:id/export` returns every card as one `text/vcard` file.

## 13. Connection check (Android)

*More → Connection check* (`ConnectionCheck.kt`) runs, from the phone, the requests other clients depend on:
`/status`, HTTPS, `GET /api/diagnostics` (what the server sees: `secure`, `origin`, `clientIp`, `trustProxy`, DAV
URLs), `PROPFIND` Depth 0 with Basic auth on `/dav/files/<user>/` and `/dav/`, `/.well-known/caldav|carddav`
without following redirects, one full upload chunk of a throwaway upload session that is then deleted (skipped on
metered networks), and the first line of `/api/events` within 8 s. Each result carries a proxy-specific fix.

<p align="center">
  <img src="docs/logo.svg" alt="Cloudy logo" width="128" height="128">
</p>

<h1 align="center">Cloudy</h1>

Your own cloud for files, sharing, calendars and contacts. You run it yourself: **one container** (server and
database inside), a browser app, and a native Android app that updates itself from GitHub releases.

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
  - edit text files;
  - **updates itself** from this repository's GitHub releases.
- **Live updates:** file lists in the browser and the Android app update by themselves when files change
  anywhere (another device, a share, WebDAV), like Google Drive.
- **Office documents:** Word, Excel, PowerPoint and OpenDocument files can be viewed (read-only) in the browser
  and the Android app; the built-in LibreOffice turns them into PDF — inside the same container.
- **Security:** passkey sign-in (fingerprint, face, screen lock or security key), two-factor authentication
  (authenticator-app codes + recovery codes) for every user, app passwords per device, brute-force protection.
- **Contacts import/export:** bring contacts in from a `.vcf` (phone Contacts app, Google, Outlook, iCloud; vCard
  2.1–4.0) or a `.csv` (Google, Outlook) in the browser or the Android app — duplicates are skipped — and export an
  address book as one `.vcf`.
- **Email:** password reset by email, share notifications and security notices through your SMTP server.
- **Administration:** users, groups, quotas, app passwords, 2FA and passkey reset, and email (SMTP) settings.

## Install

You need Docker with the Compose plugin. Three steps:

```sh
# 1. Get the compose file
mkdir cloudy && cd cloudy
curl -fsSLO https://raw.githubusercontent.com/Fahed-Alhammadi/cloudy/main/docker-compose.yml

# 2. Set the admin password (and anything else you want to change)
nano docker-compose.yml        # ADMIN_PASSWORD: ${ADMIN_PASSWORD:-your-password-here}

# 3. Start
docker compose up -d
```

Open http://localhost:8080 (or `http://<server-ip>:8080`) and sign in as `admin` with your password.

Every setting is in `docker-compose.yml` with its default, written as `${NAME:-default}`. To change one, edit
the part after `:-`. For example, to store files on your NFS mount and use port 9000:

```yaml
      - "${APP_BIND:-0.0.0.0}:${APP_PORT:-9000}:8080"
      - ${DATA_PATH:-/mnt/cloudy}:/data
```

If you'd rather not touch the compose file, put the same names in a `.env` file next to it (see
[`.env.example`](.env.example)). Values in `.env` override the defaults in the file.

If you leave `ADMIN_PASSWORD` empty, a random password is generated on first start. Read it with
`docker logs cloudy | grep "admin password"`.

**Just trying it out?** One command, no files needed:

```sh
docker run -d --name cloudy -p 8080:8080 \
  -v cloudy-data:/data -v cloudy-db:/var/lib/cloudy/db \
  ghcr.io/fahed-alhammadi/cloudy:latest
docker logs cloudy | grep "admin password"
```

The image contains Cloudy and its own PostgreSQL 17. The database is reachable only inside the container
(no network port, no password to manage). Set `DATABASE_URL` to use an external PostgreSQL instead.

**Build from source instead** (development or your own changes), from a clone of this repository:

```sh
docker compose -f deploy/docker-compose.yml --env-file .env up -d --build
```

Both compose files use the same project name and volumes, so switching between them keeps your data.

## Production

Cloudy serves plain HTTP on `APP_PORT` (default 8080). Put it behind your reverse proxy for TLS, and set
`TRUST_PROXY` to `true`. The proxy must:
- forward `Host`, `X-Forwarded-For` and `X-Forwarded-Proto`;
- allow unlimited request bodies and long timeouts (uploads arrive in 8 MB chunks; downloads stream);
- pass all HTTP methods through (WebDAV uses PROPFIND, MKCOL, MOVE, COPY, LOCK, REPORT…).

Set `APP_BIND` to `127.0.0.1` if the proxy runs on the same host.

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

**Back up** the database (`DB_PATH`) and the user files (`DATA_PATH`) together. For a consistent database dump
while Cloudy runs:

```sh
docker exec -u node cloudy pg_dump -h /run/cloudy -U cloudy cloudy > cloudy.sql
```

(Use your `APP_USER` uid instead of `node` if you changed it.)

### Storing files on NFS or another disk

User files live wherever `DATA_PATH` points. That folder holds uploads in progress, versions and trash
too, so everything moves together. To use an NFS share:

1. Mount the share on the host, e.g. `/mnt/cloudy` (add it to `/etc/fstab` so it mounts before Docker
   starts).
2. Make it writable by the user Cloudy runs as: `sudo chown 1000:1000 /mnt/cloudy`, or match the NFS
   export's owner with `APP_USER`.
3. Set `DATA_PATH` to `/mnt/cloudy` (and `APP_USER` if the owner isn't 1000:1000), in `docker-compose.yml`
   or `.env`. Keep `DB_PATH` (the database) on a local disk; databases on NFS risk corruption.
4. Run `docker compose up -d`.

If the folder isn't writable, the app refuses to start and logs exactly which uid:gid needs access.
Moving an existing install: stop the stack, copy the contents of the old storage (for the default volume:
`docker run --rm -v cloudy_app-data:/from -v /mnt/cloudy:/to alpine cp -a /from/. /to/`), set
`DATA_PATH`, and start again.

## Updates

**Server.** `docker-compose.yml` runs `ghcr.io/fahed-alhammadi/cloudy:latest`. To update to the newest
release, run this in the folder with your `docker-compose.yml`:

```sh
docker compose pull && docker compose up -d
```

The database schema migrates itself at startup. Your data stays in the volumes. The release workflow in
`.github/workflows/release.yml` publishes the image to GitHub Container Registry when you push a `v*` tag.

**Android app.** The same `v*` tag builds the signed APK and attaches it to the GitHub release. Installed apps
check the latest GitHub release when opened and once a day, and offer the update — nothing to upload to your
server. Bump `version` in `app/package.json` and `versionCode`/`versionName` in
`mobile/android/app/build.gradle.kts` before tagging (the release workflow refuses a tag that doesn't match).
New users install from `https://<your-server>/download/android` (linked on the login page), which points to the
latest release. See [mobile/README.md](mobile/README.md).

## Two-factor authentication

Each user can turn it on under *Settings → Two-factor authentication*:
1. Scan the QR code with an authenticator app (Google Authenticator, Microsoft Authenticator, Authy, 1Password…).
2. Confirm with a code.
3. Save the 10 recovery codes shown.

From then on, the web app and the Android app ask for a code after the password. A recovery code can be used
once instead, if the phone is lost.

With 2FA on, WebDAV, CalDAV and CardDAV apps must sign in with an **app password**, because those protocols
can't ask for a code. An administrator can reset 2FA for a user under *Administration → Edit user*.

## Office documents

Opening a `.docx`, `.xlsx`, `.pptx`, `.odt`, `.ods`, `.odp`, `.rtf` (or older `.doc`/`.xls`/`.ppt`) file shows a
read-only preview in the browser and in the Android app. The built-in LibreOffice converts the document to PDF the
first time it's opened (usually a second or two) and keeps the result until the file changes. To change a
document, download it and upload the new version — Cloudy keeps the old one under *Versions*.

Text files (`.txt`, `.md`, `.csv`, …) can be edited directly in the browser and the Android app.

## Email

Set up an SMTP server under *Administration → Email (SMTP)* (server, port, encryption, login, sender) and use
*Save and send test* to check it. Cloudy then:
- shows **Forgot password?** on the login page (web and Android): the user enters their username or email and
  gets a one-time link, valid 60 minutes, to choose a new password. The answer never reveals whether an account
  exists, and requests are rate-limited. A reset signs the account out everywhere;
- emails users when a file or folder is shared with them (each user can turn this off under *Settings → Profile*);
- sends a security notice when a password is changed.

Emails go to the address in each user's profile. Leave the SMTP server empty to turn email off. The SMTP password
is stored in the database and never shown again in the web UI.

## Passkeys

Users add a passkey under *Settings → Passkeys* (confirming with their password). After that they can sign in to
the web app with *Sign in with a passkey*, or by picking the passkey from the username field's autofill. No
username, password or two-factor code is needed: the passkey itself requires the device's PIN or biometrics.

- Passkeys need HTTPS (or `http://localhost`) and only work on the address they were created on. If your
  proxy doesn't forward the original `Host`, set `PUBLIC_URL` (e.g. `https://cloud.example.com`).
- The password still works, and the Android app and DAV clients keep using passwords / app passwords.
- An administrator can remove all of a user's passkeys under *Administration → Edit user*.

## Connecting other apps

Create an app password under *Settings → Connect apps and devices*, then use:

| Client | Address |
|---|---|
| WebDAV (Finder, Explorer, rclone, Cyberduck) | `https://<host>/dav/files/<username>/` |
| CalDAV / CardDAV (Thunderbird, DAVx⁵, Apple) | `https://<host>/dav/` (auto-discovered via `/.well-known/caldav` and `/.well-known/carddav`) |

## Configuration

Set these in `docker-compose.yml` (the value after `:-`) or in a `.env` file next to it.

| Variable | Default | Purpose |
|---|---|---|
| `ADMIN_USER` / `ADMIN_PASSWORD` | `admin` / random | First administrator (created on first start) |
| `APP_PORT` / `APP_BIND` | `8080` / `0.0.0.0` | Published port and interface |
| `APP_USER` | `1000:1000` | uid:gid the server runs as |
| `DATA_PATH` | `app-data` (volume) | Where user files are stored: a volume name or a host folder |
| `DB_PATH` | `db-data` (volume) | Where the built-in database is stored (local disk) |
| `DATABASE_URL` | built-in | External PostgreSQL instead of the built-in one |
| `TRUST_PROXY` | `false` | Honor `X-Forwarded-*` headers (only behind a proxy) |
| `TRASH_RETENTION_DAYS` | `30` | Days before trashed items are deleted |
| `MAX_VERSIONS` | `20` | Versions kept per file |
| `SESSION_DAYS` | `14` | How long a browser login lasts |

## Development

| Task | Command |
|---|---|
| Type-check the server | `cd app && npm install && npm run typecheck` |
| End-to-end tests (stack running) | `python3 tests/e2e.py http://localhost:8080 admin '<admin password>'` |
| Android debug build | `cd mobile/android && ./gradlew assembleDebug` (JDK 17+, Android SDK 35) |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how Cloudy works inside.

## Project layout

```
app/                 server (Node.js/TypeScript) and the HTML5 web app (app/public)
mobile/android/      native Android app (Kotlin, Jetpack Compose)
docs/                architecture notes
deploy/              docker-compose.yml that builds the image from source
tests/               end-to-end API/WebDAV/CalDAV/CardDAV tests
.github/workflows/   CI and release automation (Docker image + APK)
```

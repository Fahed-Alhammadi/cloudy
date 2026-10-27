# Cloudy Drive for macOS

Your Cloudy files in Finder, downloaded only when you open them. It's a menu bar app plus a File Provider extension
(Apple's `NSFileProviderReplicatedExtension`, the system that iCloud Drive and OneDrive use). Files only: no calendar
or contacts. The [desktop app](../desktop/README.md) is the other option: it keeps a full copy of the folders you pick.

## How it works

- **Finder:** "Cloudy" in the sidebar (`~/Library/CloudStorage/CloudyDrive-Cloudy`), with the user's files and a
  **Shared with me** folder. Every file starts as a placeholder that takes no space. Opening a file downloads it.
  Finder's *Remove Download* frees the space again, and macOS also evicts downloaded files when the disk is low.
- **Mac → server:** creating, editing, renaming, moving and deleting call the JSON API (`Provider/Extension.swift`).
  Uploads are chunked. A file with the same name replaces the server copy, which keeps the node id and makes a version.
  Deletes go to the server trash (`supportsSyncingTrash = false`). Ignored names are the same as the desktop app's.
- **Conflicts:** when a file is saved on the Mac but its server etag no longer matches the version the Mac had,
  the Mac's copy is uploaded as `name (conflicted copy <date>).ext` and the server version is downloaded.
- **Server → Mac:** the menu bar app holds the SSE stream (`/api/events`) and signals the working set on every
  event, every 5 minutes and after wake. `Remote.changes()` then walks down from the root and re-reads only folders
  whose etag changed. Server etags change all the way up the tree, so an unchanged root costs one request.
  `Shared/Index.swift` remembers what macOS was last told: per folder, its etag and a version per child. A folder
  it doesn't know yet (made in Finder, or after the index was reset) is read once and all of it reported, so every
  folder is tracked, not only the ones Finder has opened. The log says how many folders each pass read.
  "Shared with me" has no etag, so its list is compared on each pass.
- **Item ids:** node id as a string; the user's root is `.rootContainer`; `shared` is the virtual folder. Share roots
  can't be renamed, moved or deleted; read-only shares are read-only in Finder.
- **Account:** sign-in uses `POST /api/auth/token`, with the device named "Cloudy Drive (<Mac name>)". Server and user
  are kept in the App Group's UserDefaults. The token is a file in the App Group container, which macOS keeps other
  apps out of; a shared Keychain item would need a provisioning profile. Sign out revokes the token and removes the
  domain (`.preserveDirtyUserData`).
- **Switched off by default:** current macOS adds a new File Provider location switched off. The menu then shows
  *Switch On in System Settings…*, which opens Login Items & Extensions. The user enables Cloudy there (ⓘ next to
  File Providers).
- **Drive:** *Mount as a drive* (`CloudyDrive/DriveMount.swift`) mounts `/dav/files/<user>/` with macOS's WebDAV
  client (NetFS, no login dialog), using the device token as password (it is an app password, which DAV accepts).
  macOS names the volume after the user id. Remounted at launch while on (UserDefaults `mountsDrive`), ejected on
  sign out. Online only; the File Provider location stays the main way to use Cloudy in Finder.
- **Updates:** `CloudyDrive/Updater.swift` looks for the newest `drive-v*` release at launch and daily. The menu
  then shows *Update to Cloudy Drive X.Y.Z…* (also *Check for Updates…*): it downloads the `.dmg`, checks GitHub's
  SHA-256, copies the app out, checks the bundle ID and that it is signed by the same team, swaps the app, stops the
  old extension process and relaunches. That is why the menu bar app is not sandboxed; the extension is.

## Layout

```
project.yml            XcodeGen spec (CloudyDrive.xcodeproj is generated and git-ignored); macOS 14+
CloudyDrive/           menu bar app io.cloudy.drive: DriveApp (menu), AppModel (account, domain, SSE), Updater, DriveMount, SignInView
Provider/              extension io.cloudy.drive.provider: Extension, Item, Enumerator, Remote (listings, change walk)
Shared/                Account (App Group, token, domain, `log`), Index — used by both targets
Resources/             Localizable.xcstrings (5 languages, checked by tests/i18n_check.py), Assets (icon, menu bar icon)
../ios/Cloudy/Data/    API.swift + Models.swift are shared with the iPhone app
```

## Develop

```sh
drive/build.sh            # Debug build, signed for development (a File Provider only loads from a signed app)
drive/build.sh check      # unsigned compile check (CI)
open "drive/build/Build/Products/Debug/Cloudy Drive.app"
# Debug builds only: sign in without the window, and/or reset (remove the domain and account) first
open --env CLOUDY_SERVER=http://localhost:8080 --env CLOUDY_USER=admin --env CLOUDY_PASSWORD=… "drive/build/…/Cloudy Drive.app"
open --env CLOUDY_RESET=1 "drive/build/…/Cloudy Drive.app"
```

Debugging: Console.app with subsystem `io.cloudy.drive`, or
`log show --last 5m --predicate 'subsystem == "io.cloudy.drive" OR process == "Provider"'`.
`fileproviderctl dump io.cloudy.drive.provider` shows the domain state (`user-disabled`, errors per item).
A dev build's extension may need `pluginkit -e use -i io.cloudy.drive.provider`.

## Release

`scripts/version.py bump drive X.Y.Z`, then push to main. `.github/workflows/drive.yml` builds a Developer
ID–signed, notarized `.dmg` (`drive/build.sh dist`) and creates the `drive-vX.Y.Z` GitHub release. Installed apps
offer it from the menu and update themselves. Test the updater with a Debug build whose `MARKETING_VERSION` is lower
than the latest release, outside /Applications: `open --env CLOUDY_UPDATE=1 <app>` installs it without asking. Update the download
links in the root README and `docs/index.html` with each bump.

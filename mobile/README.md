# Cloudy for Android

A native Android app (Kotlin, Jetpack Compose) for your Cloudy server. Requires Android 8.0 or newer.

**Features:**
- Browse, search, upload and download files, including photos and videos from the device. Other apps can share
  files to Cloudy.
- Preview images, video, audio, PDF and text; open other files with any installed app.
- Rename, move, copy, delete and favorite files, and use the trash.
- Share by public link (with optional password) or with people and groups.
- Keep files and folders **available offline**. They're downloaded and refreshed when they change.
- **Photo backup:** new camera-roll photos and videos upload to a folder (default `Camera Uploads`), Wi-Fi only
  if you choose.
- **Calendar** (month view, repeating events) and **contacts**, synced with the server.
- **Over-the-air updates** from your own server.

## Sign-in

Enter the server address, username and password, plus a code from your authenticator app if the account
uses two-factor authentication (the field appears when needed). The password is exchanged for a device token stored in
app-private storage. Each device appears on the web under *Settings → App passwords*; revoke it there to sign
a lost phone out.

On a local network, use the server's IP and port, e.g. `http://192.168.1.10:8080`. Plain HTTP is allowed so this
works. Use HTTPS in production, and turn off plain HTTP in `res/xml/network_security_config.xml`.

## Build

You need JDK 17+ (e.g. `brew install openjdk@21`) and the Android SDK (platform 35, build-tools 35) with
`sdk.dir` in `android/local.properties` or `ANDROID_HOME`.

```sh
android/build-apk.sh        # → dist/Cloudy-<version>.apk (signed)
```

On the first run the script creates a signing key: `android/cloudy-release.keystore` with its passwords in
`android/keystore.properties`. Both are git-ignored. **Back them up.** Android only installs updates signed
with the same key.

## Updates (from GitHub releases)

1. Bump `versionCode` (must increase) and `versionName` in `android/app/build.gradle.kts`, and `version` in
   `../app/package.json` to the same version.
2. Commit and push a tag `vX.Y.Z` (matching `versionName`). The release workflow builds the APK, signs it with the
   key from the repository secrets, and attaches `Cloudy-X.Y.Z.apk` and `Cloudy.apk` to the GitHub release.

What users see:
- On launch, and in a daily background check (with a notification), the app asks GitHub for the latest release
  of `UPDATE_REPO` (set in `build.gradle.kts`; CI uses the building repository) and offers **Update** when its
  tag is newer than the installed `versionName`.
- The app downloads the APK (without Cloudy credentials), checks GitHub's SHA-256 and opens the Android
  installer, which only accepts APKs signed with the same key.
- The first time, Android asks the user to allow Cloudy to install apps.
- *More → Check for updates* checks on demand.
- New users install from `https://<your-server>/download/android` (linked on the login page), which redirects to
  the latest release's `Cloudy.apk`.

## Install manually

Copy the APK to the phone and open it (allow "Install unknown apps"), or run
`adb install dist/Cloudy-<version>.apk`.

## Code map

| Path | What it does |
|---|---|
| `data/Api.kt` | JSON API client (OkHttp, bearer token) |
| `data/Session.kt` | Server address, token and user |
| `data/Offline.kt` | Pinned files and cached folder listings |
| `data/AppUpdater.kt` | Over-the-air update check, download, checksum and install |
| `work/Workers.kt` | Background jobs: photo backup, offline sync, update check |
| `ui/*Screen.kt`, `ui/UpdateUi.kt` | Compose screens |

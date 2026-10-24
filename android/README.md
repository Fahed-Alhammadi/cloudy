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
android/build-apk.sh        # → dist/Cloudy-<version>.aab (Google Play) + .apk (adb testing), signed
```

On the first run the script creates a signing key: `android/cloudy-release.keystore` with its passwords in
`android/keystore.properties`. Both are git-ignored. **Back them up.** It is the Google Play *upload key*
(Play App Signing holds the app signing key).

## Releases (Google Play)

The app is installed and updated through Google Play only; it has no updater of its own.

The Android app has its own version, independent of the server and the iPhone app.

1. `scripts/version.py bump android X.Y.Z` (from the repo root): sets `versionName` and raises `versionCode` by one.
2. Commit and push to main. `.github/workflows/android.yml` builds the signed `.aab`, keeps it as a workflow
   artifact and, when `PLAY_SERVICE_ACCOUNT_JSON` is set, uploads it to the Play closed-testing track and tags
   `android-vX.Y.Z`. Promote to production in Play Console.

Pushes without a version bump only run the debug build. Secrets are listed at the top of the workflow.

For local testing: `adb install dist/Cloudy-<version>.apk` (signed with the upload key, so it can't update a
Play install — uninstall first).

## Code map

| Path | What it does |
|---|---|
| `data/Api.kt` | JSON API client (OkHttp, bearer token) |
| `data/Session.kt` | Server address, token and user |
| `data/Offline.kt` | Pinned files and cached folder listings |
| `work/Workers.kt` | Background jobs: photo backup, offline sync |
| `ui/*Screen.kt` | Compose screens |

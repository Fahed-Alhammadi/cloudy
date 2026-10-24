# Cloudy for iPhone

A native iPhone app (Swift, SwiftUI) for your Cloudy server. It needs iOS 17 or newer and has no third-party dependencies.

**Features:**
- Browse, search, sort, upload (photos/videos with the system picker, any file from Files) and download files.
- Preview images, video, audio, PDF and office documents with Quick Look; edit text files.
- Rename, move, copy, delete and favorite files, and use the trash.
- Share by public link (password, expiry date, uploads for folders) or with people and groups. Manage what you shared.
- Scan documents to PDF (VisionKit, real iPhone only, not the simulator); you name the scan before it uploads.
- Send files to other apps or Save to Files with the share sheet.
- **Calendar** (month view, repeating events) and **contacts** (import .vcf/.csv, export .vcf).
- Live updates: changes from other devices show up immediately.
- App lock (Face ID / Touch ID / passcode), Devices (revoke sign-ins), English, Arabic (RTL), German, French, Spanish.

Sign-in works like on Android: the password (plus a two-factor code if needed) is exchanged for a device token stored in
the Keychain. Each iPhone appears on the web under *Settings → App passwords*.

## Build and run

You need Xcode 16 or newer and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).
The Xcode project is generated from `project.yml` and is not committed.

```sh
ios/build.sh                # generate Cloudy.xcodeproj + simulator build (checks it compiles)
open ios/Cloudy.xcodeproj   # run on a simulator or your iPhone from Xcode
```

To run on a real iPhone, pick your team in Xcode → target *Cloudy* → *Signing & Capabilities* (or set
`DEVELOPMENT_TEAM` in `project.yml`).

**Local servers:** App Transport Security allows plain `http://` only for local-network addresses (IP addresses and
`.local` names), e.g. `http://192.168.1.10:8080`. Any other server needs `https://` with a valid certificate.

**Simulator shortcuts (Debug builds only):** skip the login screen and choose the first tab:

```sh
SIMCTL_CHILD_CLOUDY_SERVER=http://localhost:8080 SIMCTL_CHILD_CLOUDY_TOKEN=<device token> SIMCTL_CHILD_CLOUDY_TAB=1 \
  xcrun simctl launch booted io.cloudy.app
```

## Publishing on the App Store

Once your Apple Developer account is active:

1. **Bundle ID.** In [developer.apple.com → Identifiers](https://developer.apple.com/account/resources/identifiers), register
   `io.cloudy.app` (no extra capabilities needed). If it's taken, change `PRODUCT_BUNDLE_IDENTIFIER` in `project.yml`.
2. **App record.** In [App Store Connect](https://appstoreconnect.apple.com) → *Apps → +*: name *Cloudy* (or a variant if
   taken), primary language English, that bundle ID, SKU e.g. `cloudy-ios`.
3. **Archive and upload.** `DEVELOPMENT_TEAM=<your Team ID> ios/build.sh archive`, then in Xcode → *Window →
   Organizer* → *Distribute App → App Store Connect*. (Or Xcode → *Product → Archive*.) The build appears in TestFlight
   after processing. Test it there first.
4. **App information:**
   - Category: Productivity. Age rating: answer "None" to everything (4+).
   - Privacy policy URL: `https://fahed-alhammadi.github.io/cloudy/privacy-policy.html`
   - Support URL: `https://github.com/Fahed-Alhammadi/cloudy/issues` (or the GitHub Pages site).
   - **App Privacy:** choose *"No, we do not collect data from this app"*. Data goes only to the user's own server,
     which the developer doesn't operate (this matches `PrivacyInfo.xcprivacy`).
   - Export compliance is already answered in `Info.plist` (`ITSAppUsesNonExemptEncryption = NO`: only HTTPS through iOS).
5. **Screenshots:** iPhone 6.9" (1320×2868, e.g. iPhone 17 Pro Max / 18 Pro Max simulator), at least 3. Use the
   simulator shortcuts above with a demo account and `xcrun simctl io booted screenshot shot.png`.
6. **App Review sign-in (essential).** The app can't do anything without a server, so Review needs one. Run a Cloudy
   server reachable over **HTTPS** with demo content (folders, a photo, a PDF, an event, a contact), create a normal user
   for Apple, and enter the server address, username and password under *App Review Information → Sign-in required*.
   Add a note such as:
   > Cloudy is a client for self-hosted Cloudy servers (open source: github.com/Fahed-Alhammadi/cloudy). Enter the server
   > address, username and password above on the sign-in screen. Accounts are created by the server's administrator, so the
   > app has no sign-up and no in-app account deletion (users ask their administrator; see the privacy policy).
   Keep that server and account working until the review is done and for later updates.
7. Submit for review.

**Guidelines this app is built to respect:** no in-app account creation (so in-app account deletion, 5.1.1(v), doesn't
apply), no payments or links to buy anything (3.1), no tracking/analytics/ads (5.1.2), permission texts only for features
that use them (camera for the scanner, Face ID, add-to-Photos, local network), the system photo picker instead of full
library access, HTTPS required except on local networks, no mention of other platforms in the UI (2.3.10), privacy
policy linked in the app (sign-in screen and *More*).

## Releases

The iPhone app has its own version, independent of the server and Android.

1. `scripts/version.py bump ios X.Y.Z` (from the repo root): sets `MARKETING_VERSION` and raises the build number
   (`CURRENT_PROJECT_VERSION`) by one. App Store Connect rejects a build number it has already seen.
2. Commit and push to main. `.github/workflows/ios.yml` archives, signs and uploads the build to App Store Connect,
   then tags `ios-vX.Y.Z`. It appears in TestFlight after processing; submit it for App Review by hand.

One-time setup for the automatic upload (after the steps above have created the app in App Store Connect):
App Store Connect → *Users and Access → Integrations → App Store Connect API* → generate a key with the **Admin** role
(needed so CI can create its signing certificate), then add repository secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`,
`ASC_PRIVATE_KEY` (the contents of the downloaded `AuthKey_<id>.p8`) and `APPLE_TEAM_ID`. Without them the workflow
only builds. Locally, `DEVELOPMENT_TEAM=<id> ios/build.sh upload` does the same with your Xcode account.

## Code map

| Path | What it does |
|---|---|
| `project.yml` | XcodeGen spec (targets, versions, build settings) |
| `Cloudy/Info.plist` | Permissions texts, ATS (local networking only), export compliance, languages |
| `Cloudy/Data/API.swift` | JSON API client (URLSession, bearer token, chunked uploads, SSE) |
| `Cloudy/Data/Session.swift` | Server address, user, Keychain token |
| `Cloudy/Data/Live.swift` | Live updates (SSE → `.cloudyChange` notifications), download cache |
| `Cloudy/Data/Uploads.swift` | Uploads in progress (with background time) |
| `Cloudy/UI/*.swift` | SwiftUI screens: Files, FileActions (menus, Quick Look), ShareSheet, Lists (favorites, recent, shared, trash), Calendar, Contacts, More |
| `Cloudy/Resources/` | Assets (icon), `Localizable.xcstrings` / `InfoPlist.xcstrings` (translations), `PrivacyInfo.xcprivacy` |

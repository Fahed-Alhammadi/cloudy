#!/bin/sh
# Generates the Xcode project and builds Cloudy Drive for macOS (menu bar app + File Provider extension).
#   ./build.sh         → Debug build signed for development (Apple Development certificate), in build/
#   ./build.sh check   → unsigned compile check (CI on every push)
#   ./build.sh dist    → Release build signed with Developer ID, notarized .dmg in dist/ (CI; needs ASC_KEY_ID,
#                        ASC_ISSUER_ID, ASC_KEY_PATH for notarization, skipped without them)
# A File Provider extension only loads from a signed app, so even the debug build is signed.
# Needs Xcode and XcodeGen (`brew install xcodegen`).
set -e
cd "$(dirname "$0")"
xcodegen generate --quiet
version=$(sed -n 's/.*MARKETING_VERSION: "\(.*\)"/\1/p' project.yml)

if [ "$1" = "dist" ]; then
  set -- -allowProvisioningUpdates
  if [ -n "$ASC_KEY_ID" ]; then
    set -- "$@" -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"
  fi
  rm -rf dist && mkdir -p dist
  xcodebuild -project CloudyDrive.xcodeproj -scheme CloudyDrive -configuration Release -destination 'generic/platform=macOS' \
    -archivePath dist/CloudyDrive.xcarchive "$@" archive
  cat > dist/ExportOptions.plist <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>developer-id</string>
  <key>signingStyle</key><string>automatic</string>
</dict></plist>
PLIST
  xcodebuild -exportArchive -archivePath dist/CloudyDrive.xcarchive -exportOptionsPlist dist/ExportOptions.plist \
    -exportPath dist/export "$@"
  dmg="dist/Cloudy-Drive-$version.dmg"
  rm -rf dist/dmg && mkdir dist/dmg && cp -R "dist/export/Cloudy Drive.app" dist/dmg/ && ln -s /Applications dist/dmg/Applications
  hdiutil create -volname "Cloudy Drive" -srcfolder dist/dmg -ov -format UDZO "$dmg" >/dev/null
  codesign --sign "Developer ID Application" --timestamp "$dmg"
  if [ -n "$ASC_KEY_ID" ]; then
    xcrun notarytool submit "$dmg" --key "$ASC_KEY_PATH" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID" --wait | tee dist/notarize.log
    grep -q 'status: Accepted' dist/notarize.log
    xcrun stapler staple "$dmg"
  fi
  rm -rf dist/dmg dist/export dist/CloudyDrive.xcarchive dist/ExportOptions.plist dist/notarize.log
  echo "$dmg"
else
  if [ "$1" = "check" ]; then set -- CODE_SIGNING_ALLOWED=NO; else set -- -allowProvisioningUpdates; fi
  xcodebuild -project CloudyDrive.xcodeproj -scheme CloudyDrive -configuration Debug -destination 'platform=macOS' \
    -derivedDataPath build "$@" build > build.log 2>&1 || true
  grep -E "error|warning: .*\.swift|BUILD" build.log || true
  grep -q "BUILD SUCCEEDED" build.log
  echo "Built: drive/build/Build/Products/Debug/Cloudy Drive.app (run: open \"build/Build/Products/Debug/Cloudy Drive.app\")"
fi

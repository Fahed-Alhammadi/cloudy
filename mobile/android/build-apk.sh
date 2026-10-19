#!/bin/sh
# Builds the signed release APK into ../dist/ and writes ../dist/release.env.
# First run creates a signing key (filecloud-release.keystore + keystore.properties).
# BACK BOTH UP: every future update must be signed with the same key.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}

if [ ! -f keystore.properties ]; then
  echo "No signing key found — creating filecloud-release.keystore"
  PW=$(openssl rand -hex 16)
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore filecloud-release.keystore -alias filecloud \
    -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PW" -keypass "$PW" -dname "CN=FileCloud, O=FileCloud" >/dev/null
  printf 'storeFile=filecloud-release.keystore\nstorePassword=%s\nkeyAlias=filecloud\nkeyPassword=%s\n' "$PW" "$PW" > keystore.properties
  chmod 600 keystore.properties filecloud-release.keystore
  echo "IMPORTANT: back up mobile/android/filecloud-release.keystore and keystore.properties"
fi

./gradlew -q :app:assembleRelease
VERSION_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
mkdir -p ../dist
APK="../dist/FileCloud-$VERSION_NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
printf 'VERSION_NAME=%s\nVERSION_CODE=%s\nAPK=%s\n' "$VERSION_NAME" "$VERSION_CODE" "dist/FileCloud-$VERSION_NAME.apk" > ../dist/release.env
echo "Built $APK (version $VERSION_NAME, code $VERSION_CODE)"

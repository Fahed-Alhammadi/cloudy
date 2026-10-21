#!/bin/sh
# Builds the signed release APK (GitHub, self-updating) and the Google Play bundle (.aab) into ../dist/.
# Releases are published by CI on a v* tag. For Play, this key is the *upload key* (Play App Signing).
# First run creates a signing key (cloudy-release.keystore + keystore.properties).
# BACK BOTH UP: every future update must be signed with the same key.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}

if [ ! -f keystore.properties ]; then
  echo "No signing key found — creating cloudy-release.keystore"
  PW=$(openssl rand -hex 16)
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore cloudy-release.keystore -alias cloudy \
    -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PW" -keypass "$PW" -dname "CN=Cloudy, O=Cloudy" >/dev/null
  printf 'storeFile=cloudy-release.keystore\nstorePassword=%s\nkeyAlias=cloudy\nkeyPassword=%s\n' "$PW" "$PW" > keystore.properties
  chmod 600 keystore.properties cloudy-release.keystore
  echo "IMPORTANT: back up mobile/android/cloudy-release.keystore and keystore.properties"
fi

./gradlew -q :app:assembleGithubRelease :app:bundlePlayRelease
VERSION_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
mkdir -p ../dist
APK="../dist/Cloudy-$VERSION_NAME.apk"
AAB="../dist/Cloudy-$VERSION_NAME.aab"
cp app/build/outputs/apk/github/release/app-github-release.apk "$APK"
cp app/build/outputs/bundle/playRelease/app-play-release.aab "$AAB"
echo "Built $APK and $AAB (version $VERSION_NAME, code $VERSION_CODE)"

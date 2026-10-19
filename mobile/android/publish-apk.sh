#!/bin/sh
# Publishes the APK built by build-apk.sh to a Cloudy server, so installed apps update over the air.
# Usage: CLOUDY_URL=https://cloud.example.com CLOUDY_TOKEN=<admin app password> ./publish-apk.sh ["release notes"]
# Create the token on the web: Settings → App passwords (as an administrator).
set -e
cd "$(dirname "$0")/.."
: "${CLOUDY_URL:?set CLOUDY_URL}" "${CLOUDY_TOKEN:?set CLOUDY_TOKEN (an admin app password)}"
[ -f dist/release.env ] || { echo "Run android/build-apk.sh first"; exit 1; }
. dist/release.env
NOTES=$(printf %s "${1:-}" | od -An -tx1 | tr -d ' \n' | sed 's/../%&/g')
curl -fsS -X POST \
  -H "Authorization: Bearer $CLOUDY_TOKEN" -H "X-Cloudy-Request: 1" -H "Content-Type: application/octet-stream" \
  --data-binary "@$APK" \
  "$CLOUDY_URL/api/admin/app/android?versionCode=$VERSION_CODE&versionName=$VERSION_NAME&notes=$NOTES"
echo
echo "Published Cloudy $VERSION_NAME ($VERSION_CODE). Installed apps will offer the update."

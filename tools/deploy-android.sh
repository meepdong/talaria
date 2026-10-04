#!/bin/bash
# /opt/talaria/tools/deploy-android.sh
set -euo pipefail
cd /opt/talaria
APK_PATH="client/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
DEST_DIR="/var/www/talaria-updates"

# Copy APK
cp "$APK_PATH" "$DEST_DIR/latest.apk"

# Generate version.json
VERSION_CODE=$(aapt dump badging "$APK_PATH" | grep versionCode | sed 's/.*versionCode=//' | sed 's/ .*//')
VERSION_NAME=$(aapt dump badging "$APK_PATH" | grep versionName | sed "s/.*versionName='//" | sed "s/'.*//")
GIT_SHA=$(git rev-parse --short HEAD)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
APK_SHA=$(sha256sum "$APK_PATH" | cut -d' ' -f1)

cat > "$DEST_DIR/version.json" << JSON
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "gitSha": "$GIT_SHA",
  "timestamp": "$TIMESTAMP",
  "minVersionCode": 1,
  "changelogUrl": "https://update.talaria.ts.net/changelog.md",
  "apkUrl": "https://update.talaria.ts.net/latest.apk",
  "apkSha256": "$APK_SHA"
}
JSON

echo "Deployed v$VERSION_NAME ($VERSION_CODE) - $GIT_SHA"
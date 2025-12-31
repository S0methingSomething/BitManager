#!/bin/bash
set -e

VERSION=$1
if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>"
  exit 1
fi

VERSION=${VERSION#v}

echo "Building APK..."
./gradlew assembleRelease

# Decode signing key
echo "$SIGNING_KEY" | base64 -d > release.keystore

# Sign APK
APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="BitManager-v${VERSION}.apk"

jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore release.keystore \
  -storepass "$KEY_STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  "$APK_PATH" "$ALIAS"

# Align APK
zipalign -v 4 "$APK_PATH" "$SIGNED_APK"

# Cleanup
rm release.keystore

echo "✓ Built and signed: $SIGNED_APK"

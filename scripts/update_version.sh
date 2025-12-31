#!/bin/bash
set -e

VERSION=$1
if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>"
  exit 1
fi

# Remove 'v' prefix if present
VERSION=${VERSION#v}

# Extract version parts
IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION"
VERSION_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

echo "Updating to version $VERSION (code: $VERSION_CODE)"

# Update build.gradle
sed -i "s/versionCode [0-9]*/versionCode $VERSION_CODE/" app/build.gradle
sed -i "s/versionName \"[^\"]*\"/versionName \"$VERSION\"/" app/build.gradle

echo "✓ Updated app/build.gradle"

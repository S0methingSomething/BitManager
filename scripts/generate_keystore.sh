#!/usr/bin/env bash
set -e

echo "Generating keystore for signing..."

# Generate keystore
keytool -genkey -v -keystore release.keystore -alias bitmanager \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=BitManager, OU=Dev, O=BitManager, L=Unknown, S=Unknown, C=US"

# Convert to base64 for GitHub secrets
echo ""
echo "Add these to GitHub Secrets:"
echo "SIGNING_KEY=$(base64 -w 0 release.keystore)"
echo "ALIAS=bitmanager"
echo "KEY_STORE_PASSWORD=android"
echo "KEY_PASSWORD=android"

rm release.keystore

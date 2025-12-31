# Building BitManager

## Quick Start

Since Codespaces doesn't have Android SDK, build locally:

```bash
git clone https://github.com/S0methingSomething/BitManager.git
cd BitManager
./gradlew assembleDebug
```

APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

## Or Download Pre-built

Once you add signing secrets and push a `feat:` commit, GitHub Actions will build and release automatically.

For now, you can:
1. Clone the repo locally
2. Open in Android Studio
3. Build → Build APK

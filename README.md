# BitManager

ReVanced-style patcher for BitLife. Patches are fetched from this repo automatically.

## Features

- **Selectable patches** - Choose which features to unlock
- **Auto-updates** - Patches fetched from GitHub, no app update needed
- **Shizuku-based** - No root required, just Shizuku

## Available Patches

| Patch | Description |
|-------|-------------|
| Unlock All IAPs | Unlocks all in-app purchases |
| Unlock BitPass | Unlocks BitPass subscription |
| Unlock Items | Use any item |
| No Streak Loss | Never lose streaks |

## Requirements

- Android 7.0+
- Shizuku installed and running
- BitLife installed

## Usage

1. Install Shizuku and start it
2. Install BitManager
3. Open BitManager, select patches
4. Tap Apply
5. Restart BitLife

## Building

### Automated Releases (Semantic Release)

Releases are automated using semantic-release. Just commit with conventional commits:

```bash
# Features (minor version bump)
git commit -m "feat: add new patch type"

# Fixes (patch version bump)
git commit -m "fix: resolve crash on Android 12"

# Breaking changes (major version bump)
git commit -m "feat!: redesign UI"
# or
git commit -m "feat: new feature

BREAKING CHANGE: old API removed"
```

Push to `main` and semantic-release will:
1. Determine version bump from commits
2. Update `versionCode` and `versionName` in `build.gradle`
3. Build and sign APK
4. Create GitHub release with APK
5. Generate CHANGELOG

### Manual Build
### Manual Build
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Setup Signing (First Time)
### Setup Signing (First Time)
```bash
./scripts/generate_keystore.sh
```
Add the output secrets to GitHub: Settings → Secrets → Actions

## Adding New Versions

When BitLife updates:

1. Get the APK
2. Run Il2CppDumper:
   ```bash
   Il2CppDumper libil2cpp.so global-metadata.dat output
   ```
3. Extract offsets:
   ```bash
   python scripts/extract_offsets.py output/dump.cs 3.XX > patches/3.XX.json
   ```
4. Push to this repo

## Building

```bash
./gradlew assembleRelease
```

## Structure

```
BitManager/
├── app/                    # Android app
├── patches/                # Patch definitions per version
│   └── 3.22.json
└── scripts/
    └── extract_offsets.py  # Generate patches from dump
```

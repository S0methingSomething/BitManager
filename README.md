# BitManager

APK patcher for BitLife with CoreX pairip bypass support.

## Architecture

**Single codebase** - Core patching logic in Java, used by both CLI and Android app:

```
BitManager/
├── core/                      # Java library (THE patcher)
│   ├── Patcher.java          # Core patching logic
│   ├── Main.java             # CLI entry point
│   └── lib_Pairip_CoreX.so   # Pairip bypass hook library
│
├── cli/
│   └── patcher.py            # Python wrapper (calls core JAR)
│
└── app/                       # Android app (uses core as dependency)
    └── MainActivity.java     # UI that calls core.Patcher
```

## Usage

### CLI (PC)

```bash
# Build core JAR
./gradlew :core:jar

# Patch with Python wrapper
python3 cli/patcher.py input.apk --corex

# Or call Java directly
java -jar core/build/libs/core.jar input.apk --corex
```

### Android App

Install the APK and use the UI. It uses the same `core` module internally.

## CoreX Pairip Bypass

The `--corex` flag enables Level 3 pairip bypass by:
1. Adding `lib_Pairip_CoreX.so` (926KB hook library) to the APK
2. Patching `VMRunner.smali` to load the hook library
3. The hook intercepts pairip's `executeVM` at runtime

This bypasses even Level 3 protection (where code is physically removed from binaries).

## Options

```
--version X.X.X   Specify APK version
--keystore PATH   Custom keystore for signing
--corex           Enable CoreX pairip bypass
--no-patches      Skip JSON patches (CoreX only)
-o OUTPUT         Output APK path
```

## Requirements

- Java 17+
- apktool
- aapt (for version detection)
- apksigner or jarsigner (for signing)

## Development

```bash
# Build core JAR
./gradlew :core:jar

# Build Android app
./gradlew :app:assembleDebug

# Test CLI
python3 cli/patcher.py test.apk --corex
```

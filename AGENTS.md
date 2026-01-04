# BitLife Modding Knowledge Base

## Pairip Protection Analysis

### Protection Levels
- **Level 1-2**: Signature/license check only - can bypass by modifying AndroidManifest
- **Level 3**: Code physically removed from libil2cpp.so, stored as encrypted VM bytecode in assets

### How Level 3 Works
1. Pairip encrypts/strips the entire `.text` section of libil2cpp.so
2. Stores removed code as encrypted bytecode in `assets/` (random 16-char filenames like `3GizXtM4iLfvd6iF`)
3. `VMRunner.executeVM()` decrypts and runs bytecode at runtime
4. Code is restored IN MEMORY only, never written to disk

### Key Files
- `libpairipcore.so` - The protection library (~550KB)
- `assets/<random>` - VM bytecode files (~150KB each, 14-30 files per version)
- `com/pairip/VMRunner.smali` - Java side that loads pairipcore
- `com/pairip/SignatureCheck.java` - Signature verification

### Bypass Methods
1. **Bsdiff patch** (WORKING) - 31KB patch restores 80MB stripped code
2. **CoreX hook** - Load hook library before pairipcore
3. **Runtime dump** - PADumper/Zygisk dumps restored .so from memory
4. **AndroidManifest redirect** - Only works for Level 1-2

## BitLife Technical Details

### Version Info
- Package: `com.candywriter.bitlife`
- Engine: Unity IL2CPP
- Analyzed versions: 3.21.4, 3.22

### File Sizes
- APK: ~214MB
- libil2cpp.so: ~80MB
- libpairipcore.so: ~550KB
- Bsdiff patch: ~31KB

### Key Classes (from Il2CppDumper)

#### EncryptionManager
```
Offset 0x1ADF97C - DecodeAndDecryptString
Offset 0x1ADFAE0 - EncryptString  
Offset 0x1ADFA14 - DecryptString
Offset 0x1ADFBC8 - DefaultCipherKey
Offset 0x1ADFD00 - XORCipherString
Offset 0x1ADFC08 - ObfuscateString
```
- Cipher key: `com.wtfapps.apollo16`
- Uses XOR cipher with obfuscated key

#### MonetizationVars (offsets 0x10-0x66+)
All boolean fields for purchases:
- UserBoughtBitizenship
- UserBoughtGodMode
- UserBoughtBossMode
- UserBoughtSpecialCareer* (Politician, Athlete, Musician, etc.)
- UserBoughtExpansionPack* (Investor, Landlord, BlackMarket, etc.)
- UserBoughtBundle* (Billionaire, Crime, FameAndFortune, etc.)

### ELF Structure
- `.text` section starts at offset 0x181c6d4
- Original: encrypted/garbage data
- Patched: valid ARM64 instructions

## Mod APK Analysis

### libPDALIFE.so
- Size: ~1MB
- Purpose: Hook library + mod menu
- Loaded via modified smali

### How Mods Work
1. Hook library injected at app start
2. Hooks game functions (getters/setters)
3. Returns modified values (money, stats, unlocks)
4. Mod menu overlay for user control

## BitManager Implementation

### Architecture
- `core/` - Java module with Patcher.java (shared CLI + Android)
- `app/` - Android UI, calls core module
- `cli/` - Python wrapper for CLI usage

### Bsdiff Patching
- Uses `io.sigpipe.jbsdiff` library (pure Java)
- Works on both desktop and Android
- Patch files stored in `patches/bsdiff/<version>.bsdiff`

### Patch Config
```java
PatchConfig {
    boolean corex;           // CoreX hook bypass
    File bsdiffPatch;        // Bsdiff patch file
    List<NativePatch> patches; // Offset-based patches
    String keystore;         // Signing keystore
}
```

## Creating a Mod Menu

### Requirements
1. **Hook library** - Dobby, Substrate, or custom
2. **Il2Cpp bridge** - Call game functions from hooks
3. **UI overlay** - ImGui or custom Android views
4. **Offset database** - Per-version function addresses

### Hook Library Options
- **Dobby** - Lightweight, works on Android
- **Substrate** - Cydia Substrate, mature
- **And64InlineHook** - Simple ARM64 hooking
- **frida-gadget** - JavaScript-based, flexible

### Mod Menu Features (typical)
- God Mode (no death)
- Unlimited Money
- Max Stats (health, happiness, smarts, looks)
- Unlock all purchases
- Time manipulation
- Custom events

### Implementation Steps
1. Create native library (.so) with hooks
2. Define hook points from Il2CppDumper offsets
3. Build mod menu UI (ImGui recommended)
4. Inject library via smali modification
5. Handle version differences with offset configs

## Tools & Resources

### Analysis Tools
- **Il2CppDumper** - Extract symbols from IL2CPP games
- **apktool** - Decompile/recompile APKs
- **Ghidra/IDA** - Disassembly and analysis
- **frida** - Dynamic instrumentation

### Relevant Repos
- `github.com/Solaree/pairipcore` - Pairip research
- `github.com/Perfare/Il2CppDumper` - Symbol extraction
- `github.com/jbro129/Android-Hooking-Patching-Guide` - Hooking guide

### Community Resources
- Platinmods forums - Modding tutorials
- GitHub APKiD issue #329 - Pairip discussion

## Version-Specific Data

### BitLife 3.21.4
- libil2cpp.so MD5 (original): `1297dcd2ecf323f9c62fa48182e10984`
- libil2cpp.so MD5 (patched): `1cba6fab8e87e6b03c0b75f32770729a`
- Bsdiff patch size: 31KB
- VM bytecode files: ~30

### BitLife 3.22
- Different VM bytecode files
- Different libpairipcore.so (548KB vs 564KB)
- Needs separate bsdiff patch

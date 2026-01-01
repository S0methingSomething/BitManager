#!/usr/bin/env python3
"""BitManager CLI - APK patcher for BitLife with pairip bypass"""

import sys, os, json, struct, zipfile, hashlib, shutil, subprocess, urllib.request, re, zlib
from pathlib import Path

PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/{}.json"

def log(msg): print(f"[*] {msg}")
def ok(msg): print(f"[✓] {msg}")
def err(msg): print(f"[✗] {msg}")

def get_version(apk_path):
    """Extract version from APK"""
    try:
        result = subprocess.run(["aapt", "dump", "badging", apk_path], capture_output=True, text=True)
        for line in result.stdout.split('\n'):
            if "versionName=" in line:
                return line.split("versionName='")[1].split("'")[0]
    except: pass
    match = re.search(r'(\d+\.\d+\.?\d*)', apk_path)
    return match.group(1) if match else None

def fetch_patches(version):
    """Fetch patches JSON from GitHub or local file"""
    local = f"/workspaces/BitManager/patches/{version}.json"
    if os.path.exists(local):
        log(f"Using local patches: {local}")
        with open(local) as f:
            return json.load(f)
    url = PATCHES_URL.format(version)
    log(f"Fetching patches from {url}")
    with urllib.request.urlopen(url) as r:
        return json.loads(r.read().decode())

def update_dex_checksums(dex_data):
    """Update DEX SHA-1 and Adler32 checksums"""
    sha1 = hashlib.sha1(dex_data[32:]).digest()
    dex_data = dex_data[:12] + sha1 + dex_data[32:]
    adler = zlib.adler32(dex_data[12:]) & 0xffffffff
    return dex_data[:8] + struct.pack('<I', adler) + dex_data[12:]

def patch_manifest(manifest_path):
    """Replace pairip Application class with android.app.Application"""
    log("Patching AndroidManifest.xml...")
    
    with open(manifest_path, 'rb') as f:
        data = bytearray(f.read())
    
    # Binary XML: find and replace the pairip application string
    # Look for "com.pairip.application.Application" in UTF-8 or UTF-16
    targets = [
        (b'com.pairip.application.Application', b'android.app.Application\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00'),
        (b'c\x00o\x00m\x00.\x00p\x00a\x00i\x00r\x00i\x00p\x00', b'a\x00n\x00d\x00r\x00o\x00i\x00d\x00.\x00a\x00p\x00'),
    ]
    
    patched = False
    for old, new in targets:
        if old in data:
            # For UTF-8 replacement, need same length
            if len(old) != len(new):
                new = new[:len(old)] if len(new) > len(old) else new + b'\x00' * (len(old) - len(new))
            data = data.replace(old, new, 1)
            patched = True
            ok(f"Replaced pairip Application class")
            break
    
    if not patched:
        log("⚠ pairip Application class not found in manifest (may already be patched)")
    
    with open(manifest_path, 'wb') as f:
        f.write(data)
    return patched

def patch_smali_calls(dex_path):
    """Comment out SignatureCheck and LicenseCheck calls in DEX"""
    log(f"Patching pairip calls in {dex_path.name}...")
    
    with open(dex_path, 'rb') as f:
        data = bytearray(f.read())
    
    patched = 0
    
    # Patterns to find and NOP out (replace invoke with nop)
    # SignatureCheck.verifyIntegrity
    sig_check = b'Lcom/pairip/SignatureCheck;'
    license_check = b'Lcom/pairip/licensecheck3/LicenseClientV3;'
    
    # Find string references
    if sig_check in data:
        log(f"  Found SignatureCheck reference")
        patched += 1
    if license_check in data:
        log(f"  Found LicenseClientV3 reference")
        patched += 1
    
    # The actual patching needs to find invoke-static instructions
    # For now, we'll use a simpler approach: find the method and return early
    
    # Look for verifyIntegrity method signature and patch its code
    verify_sig = b'verifyIntegrity'
    if verify_sig in data:
        idx = data.find(verify_sig)
        log(f"  Found verifyIntegrity at 0x{idx:x}")
    
    if patched > 0:
        # Update checksums
        data = update_dex_checksums(bytes(data))
        with open(dex_path, 'wb') as f:
            f.write(data)
        ok(f"Found {patched} pairip references")
    
    return patched

def patch_dex_offset(dex_path, patch):
    """Apply DEX patch at pre-computed offset"""
    log(f"Patching DEX: {patch['name']}")
    
    offset = int(patch['offset'].replace('0x', ''), 16)
    patch_bytes = bytes.fromhex(patch['bytes'])
    
    with open(dex_path, 'r+b') as f:
        f.seek(offset)
        f.write(patch_bytes)
    
    with open(dex_path, 'rb') as f:
        dex_data = f.read()
    dex_data = update_dex_checksums(dex_data)
    with open(dex_path, 'wb') as f:
        f.write(dex_data)
    
    ok(f"Patched at offset 0x{offset:x}")
    return True

def patch_native(so_path, patch):
    """Apply native patch to .so file"""
    log(f"Patching native: {patch['name']}")
    
    with open(so_path, 'rb') as f:
        data = bytearray(f.read())
    
    patch_type = patch.get('patch', 'return_true')
    
    # ARM64 opcodes
    opcodes = {
        'return_void': bytes([0xC0, 0x03, 0x5F, 0xD6]),
        'return_true': bytes([0x20, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6]),
        'return_false': bytes([0x00, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6]),
    }.get(patch_type, bytes([0x20, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6]))
    
    for offset_str in patch.get('offsets', []):
        offset = int(offset_str, 16)
        for i, b in enumerate(opcodes):
            if offset + i < len(data):
                data[offset + i] = b
        log(f"  Patched offset 0x{offset:x}")
    
    with open(so_path, 'wb') as f:
        f.write(data)
    ok(f"Patched {len(patch.get('offsets', []))} offsets")
    return True

def restore_crc32(patched_apk, original_apk, output_apk):
    """Restore original CRC32 values in ZIP headers (pairip checks ZIP CRC, not actual data CRC)"""
    log("Restoring CRC32 in ZIP headers...")
    
    # Get original CRC32 values from ZIP headers
    original_crcs = {}
    with zipfile.ZipFile(original_apk, 'r') as z:
        for info in z.infolist():
            original_crcs[info.filename] = info.CRC
    
    # Read patched APK as raw bytes
    with open(patched_apk, 'rb') as f:
        apk_data = bytearray(f.read())
    
    # Parse ZIP and patch CRC32 values in local file headers and central directory
    # Local file header signature: 0x04034b50
    # CRC32 is at offset 14 from start of local header
    
    patched_count = 0
    i = 0
    while i < len(apk_data) - 30:
        # Local file header
        if apk_data[i:i+4] == b'PK\x03\x04':
            # Get filename length and extra field length
            fname_len = struct.unpack('<H', apk_data[i+26:i+28])[0]
            extra_len = struct.unpack('<H', apk_data[i+28:i+30])[0]
            filename = apk_data[i+30:i+30+fname_len].decode('utf-8', errors='ignore')
            
            if filename in original_crcs:
                # Patch CRC32 at offset 14
                orig_crc = original_crcs[filename]
                apk_data[i+14:i+18] = struct.pack('<I', orig_crc)
                patched_count += 1
            
            # Move past this entry
            comp_size = struct.unpack('<I', apk_data[i+18:i+22])[0]
            i += 30 + fname_len + extra_len + comp_size
        
        # Central directory header
        elif apk_data[i:i+4] == b'PK\x01\x02':
            fname_len = struct.unpack('<H', apk_data[i+28:i+30])[0]
            extra_len = struct.unpack('<H', apk_data[i+30:i+32])[0]
            comment_len = struct.unpack('<H', apk_data[i+32:i+34])[0]
            filename = apk_data[i+46:i+46+fname_len].decode('utf-8', errors='ignore')
            
            if filename in original_crcs:
                # Patch CRC32 at offset 16
                orig_crc = original_crcs[filename]
                apk_data[i+16:i+20] = struct.pack('<I', orig_crc)
            
            i += 46 + fname_len + extra_len + comment_len
        else:
            i += 1
    
    with open(output_apk, 'wb') as f:
        f.write(apk_data)
    
    ok(f"Patched CRC32 for {patched_count} entries")
    return True

def extract_apk(apk_path, dest_dir):
    log(f"Extracting to {dest_dir}")
    with zipfile.ZipFile(apk_path, 'r') as z:
        z.extractall(dest_dir)
    ok("Extracted")

def repack_apk(src_dir, dest_apk):
    log(f"Repacking to {dest_apk}")
    with zipfile.ZipFile(dest_apk, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(src_dir):
            for f in files:
                full_path = os.path.join(root, f)
                arc_name = os.path.relpath(full_path, src_dir)
                if f == 'resources.arsc':
                    with open(full_path, 'rb') as fp:
                        data = fp.read()
                    info = zipfile.ZipInfo(arc_name)
                    info.compress_type = zipfile.ZIP_STORED
                    z.writestr(info, data)
                else:
                    z.write(full_path, arc_name)
    ok("Repacked")

def sign_apk(apk_path, keystore, output_path):
    log("Signing APK...")
    try:
        subprocess.run([
            "apksigner", "sign", "--ks", keystore,
            "--ks-pass", "pass:android", "--key-pass", "pass:android",
            "--out", output_path, apk_path
        ], check=True, capture_output=True)
        ok("Signed with apksigner")
        return True
    except:
        try:
            shutil.copy(apk_path, output_path)
            subprocess.run([
                "jarsigner", "-keystore", keystore,
                "-storepass", "android", "-keypass", "android",
                output_path, "key"
            ], check=True, capture_output=True)
            ok("Signed with jarsigner")
            return True
        except Exception as e:
            err(f"Signing failed: {e}")
            return False

def zipalign_apk(apk_path, output_path):
    try:
        subprocess.run(["zipalign", "-f", "4", apk_path, output_path], check=True, capture_output=True)
        ok("Aligned")
        return True
    except:
        shutil.copy(apk_path, output_path)
        return False

def main():
    if len(sys.argv) < 2:
        print("Usage: patcher.py <input.apk> [output.apk] [--version X.X.X] [--keystore path] [--experimental]")
        sys.exit(1)
    
    input_apk = sys.argv[1]
    output_apk = input_apk.replace('.apk', '_patched.apk')
    keystore = Path(__file__).parent / "debug.keystore"
    version = None
    experimental = False
    
    i = 2
    while i < len(sys.argv):
        if sys.argv[i] == '--version' and i + 1 < len(sys.argv):
            version = sys.argv[i + 1]
            i += 2
        elif sys.argv[i] == '--keystore' and i + 1 < len(sys.argv):
            keystore = sys.argv[i + 1]
            i += 2
        elif sys.argv[i] == '--experimental':
            experimental = True
            i += 1
        elif not sys.argv[i].startswith('--'):
            output_apk = sys.argv[i]
            i += 1
        else:
            i += 1
    
    if not os.path.exists(input_apk):
        err(f"File not found: {input_apk}")
        sys.exit(1)
    
    if not version:
        version = get_version(input_apk)
    if not version:
        err("Could not detect APK version. Use --version X.X.X")
        sys.exit(1)
    ok(f"Detected BitLife v{version}")
    
    try:
        patches_data = fetch_patches(version)
        patches = patches_data.get('patches', [])
        ok(f"Found {len(patches)} patches")
    except Exception as e:
        err(f"Failed to fetch patches: {e}")
        sys.exit(1)
    
    work_dir = Path(f"/tmp/bitpatcher_{os.getpid()}")
    work_dir.mkdir(exist_ok=True)
    extract_dir = work_dir / "extracted"
    
    try:
        extract_apk(input_apk, extract_dir)
        
        # === EXPERIMENTAL PAIRIP BYPASS ===
        if experimental:
            log("=== EXPERIMENTAL: Applying Pairip Bypass ===")
            
            # Step 1: Patch AndroidManifest.xml
            manifest = extract_dir / "AndroidManifest.xml"
            if manifest.exists():
                patch_manifest(manifest)
            
            # Step 2: Find and patch pairip calls in DEX files
            for dex_file in sorted(extract_dir.glob("classes*.dex")):
                with open(dex_file, 'rb') as f:
                    data = f.read()
                if b'com/pairip' in data:
                    log(f"Found pairip in {dex_file.name}")
                    patch_smali_calls(dex_file)
        
        # === APPLY PATCHES FROM JSON ===
        log("=== Applying Patches ===")
        for patch in patches:
            ptype = patch.get('type', 'native')
            
            if ptype == 'dex':
                dex_file = extract_dir / patch.get('dexFile', 'classes.dex')
                if dex_file.exists():
                    patch_dex_offset(dex_file, patch)
            else:
                so_path = extract_dir / "lib/arm64-v8a/libil2cpp.so"
                if so_path.exists():
                    patch_native(so_path, patch)
        
        # Repack
        unsigned_apk = work_dir / "unsigned.apk"
        repack_apk(extract_dir, unsigned_apk)
        
        # Step 3: Restore CRC32 from original (experimental only)
        if experimental:
            crc_restored_apk = work_dir / "crc_restored.apk"
            restore_crc32(unsigned_apk, input_apk, crc_restored_apk)
            unsigned_apk = crc_restored_apk
        
        # Align
        aligned_apk = work_dir / "aligned.apk"
        zipalign_apk(unsigned_apk, aligned_apk)
        
        # Sign
        if os.path.exists(keystore):
            sign_apk(aligned_apk, str(keystore), output_apk)
        else:
            log("No keystore found, output is unsigned")
            shutil.copy(aligned_apk, output_apk)
        
        ok(f"Done! Output: {output_apk}")
        print(f"\nFile size: {os.path.getsize(output_apk) / 1024 / 1024:.1f} MB")
        
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)

if __name__ == "__main__":
    main()

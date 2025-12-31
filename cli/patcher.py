#!/usr/bin/env python3
"""BitManager CLI - APK patcher for BitLife"""

import sys, os, json, struct, zipfile, hashlib, shutil, subprocess, urllib.request
from pathlib import Path

PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/{}.json"

def log(msg): print(f"[*] {msg}")
def ok(msg): print(f"[✓] {msg}")
def err(msg): print(f"[✗] {msg}")

def get_version(apk_path):
    """Extract version from APK using aapt or AndroidManifest.xml"""
    # Try aapt first
    try:
        result = subprocess.run(["aapt", "dump", "badging", apk_path], capture_output=True, text=True)
        for line in result.stdout.split('\n'):
            if "versionName=" in line:
                return line.split("versionName='")[1].split("'")[0]
    except: pass
    
    # Fallback: parse binary AndroidManifest.xml
    try:
        with zipfile.ZipFile(apk_path, 'r') as z:
            manifest = z.read('AndroidManifest.xml')
            # Look for versionName string in binary XML
            # Format: UTF-16LE string after "versionName" marker
            idx = manifest.find(b'versionName')
            if idx != -1:
                # Search for version pattern like "3.21.4"
                import re
                text = manifest[idx:idx+200].decode('utf-8', errors='ignore')
                match = re.search(r'(\d+\.\d+\.?\d*)', text)
                if match:
                    return match.group(1)
    except: pass
    
    # Last resort: ask user or use filename
    import re
    match = re.search(r'(\d+\.\d+\.?\d*)', apk_path)
    if match:
        return match.group(1)
    return None

def fetch_patches(version):
    """Fetch patches JSON from GitHub"""
    url = PATCHES_URL.format(version)
    log(f"Fetching patches from {url}")
    with urllib.request.urlopen(url) as r:
        return json.loads(r.read().decode())

def compute_crc(data):
    import zlib
    return zlib.crc32(data) & 0xffffffff

def update_dex_checksums(dex_data):
    """Update DEX SHA-1 signature (offset 12) and Adler32 checksum (offset 8)"""
    import zlib
    # SHA-1 of bytes[32:]
    sha1 = hashlib.sha1(dex_data[32:]).digest()
    dex_data = dex_data[:12] + sha1 + dex_data[32:]
    # Adler32 of bytes[12:]
    adler = zlib.adler32(dex_data[12:]) & 0xffffffff
    dex_data = dex_data[:8] + struct.pack('<I', adler) + dex_data[12:]
    return dex_data

def find_method_code_offset(dex_data, class_name, method_name):
    """Find method's code offset in DEX file"""
    # Simple search for method - look for class descriptor then method
    class_bytes = class_name.encode('utf-8')
    method_bytes = method_name.encode('utf-8')
    
    # Find string IDs
    class_pos = dex_data.find(class_bytes)
    method_pos = dex_data.find(method_bytes)
    
    if class_pos == -1 or method_pos == -1:
        return None
    
    # Search for code_item with insns after method reference
    # This is simplified - real implementation would parse DEX structure
    # Look for the method's code by finding return-void pattern near method
    search_start = method_pos
    for i in range(search_start, min(search_start + 10000, len(dex_data) - 20)):
        # Look for code_item header pattern followed by instructions
        # registers_size (2), ins_size (2), outs_size (2), tries_size (2), 
        # debug_info_off (4), insns_size (4), then insns
        if dex_data[i:i+2] == b'\x01\x00':  # 1 register
            insns_size = struct.unpack('<I', dex_data[i+12:i+16])[0]
            if 1 <= insns_size <= 1000:
                return i + 16  # offset to insns
    return None

def patch_dex(dex_path, patch):
    """Apply DEX patch - insert return-void at method start"""
    log(f"Patching DEX: {patch['name']}")
    
    class_name = patch['className']
    method_name = patch['methodName']
    
    # Try androguard first
    try:
        from androguard.core.dex import DEX
        dex = DEX(open(dex_path, 'rb').read())
        
        for cls in dex.get_classes():
            if cls.get_name() == class_name:
                for method in cls.get_methods():
                    if method.get_name() == method_name:
                        code = method.get_code()
                        if code:
                            offset = code.get_off() + 16  # Skip code_item header
                            log(f"Found {method_name} at offset 0x{offset:x}")
                            
                            with open(dex_path, 'r+b') as f:
                                f.seek(offset)
                                if patch.get('patch') == 'return_void':
                                    f.write(bytes([0x0e, 0x00]))
                            
                            # Update checksums
                            with open(dex_path, 'rb') as f:
                                dex_data = bytearray(f.read())
                            dex_data = update_dex_checksums(bytes(dex_data))
                            with open(dex_path, 'wb') as f:
                                f.write(dex_data)
                            
                            ok(f"Patched {method_name}")
                            return True
        err(f"Method {class_name}->{method_name} not found")
        return False
    except ImportError:
        err("androguard not installed, skipping DEX patch")
        return False
    except Exception as e:
        err(f"DEX patch failed: {e}")
        return False

def patch_native(so_path, patch):
    """Apply native patch to .so file"""
    log(f"Patching native: {patch['name']}")
    
    with open(so_path, 'rb') as f:
        data = bytearray(f.read())
    
    patch_type = patch.get('patch', 'return_void')
    
    # ARM64 opcodes
    if patch_type == 'return_void':
        opcodes = bytes([0xC0, 0x03, 0x5F, 0xD6])  # ret
    elif patch_type == 'return_true':
        opcodes = bytes([0x20, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6])  # mov x0, #1; ret
    elif patch_type == 'return_false':
        opcodes = bytes([0x00, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6])  # mov x0, #0; ret
    else:
        err(f"Unknown patch type: {patch_type}")
        return False
    
    for offset_str in patch.get('offsets', []):
        offset = int(offset_str, 16)
        log(f"  Patching offset 0x{offset:x}")
        for i, b in enumerate(opcodes):
            if offset + i < len(data):
                data[offset + i] = b
    
    with open(so_path, 'wb') as f:
        f.write(data)
    
    ok(f"Patched {len(patch.get('offsets', []))} offsets")
    return True

def extract_apk(apk_path, dest_dir):
    """Extract APK to directory"""
    log(f"Extracting to {dest_dir}")
    with zipfile.ZipFile(apk_path, 'r') as z:
        z.extractall(dest_dir)
    ok("Extracted")

def repack_apk(src_dir, dest_apk):
    """Repack directory to APK with proper alignment"""
    log(f"Repacking to {dest_apk}")
    
    with zipfile.ZipFile(dest_apk, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(src_dir):
            for f in files:
                full_path = os.path.join(root, f)
                arc_name = os.path.relpath(full_path, src_dir)
                
                # Store uncompressed for Android R+ compatibility
                if f == 'resources.arsc' or f.endswith('.so') or f.endswith('.png'):
                    with open(full_path, 'rb') as fp:
                        data = fp.read()
                    info = zipfile.ZipInfo(arc_name)
                    info.compress_type = zipfile.ZIP_STORED
                    z.writestr(info, data)
                else:
                    z.write(full_path, arc_name)
    ok("Repacked")

def sign_apk(apk_path, keystore, output_path):
    """Sign APK using apksigner or jarsigner"""
    log("Signing APK...")
    
    # Try apksigner first
    try:
        subprocess.run([
            "apksigner", "sign",
            "--ks", keystore,
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--out", output_path,
            apk_path
        ], check=True, capture_output=True)
        ok("Signed with apksigner")
        return True
    except: pass
    
    # Fallback to jarsigner
    try:
        shutil.copy(apk_path, output_path)
        subprocess.run([
            "jarsigner",
            "-keystore", keystore,
            "-storepass", "android",
            "-keypass", "android",
            output_path, "key"
        ], check=True, capture_output=True)
        ok("Signed with jarsigner")
        return True
    except Exception as e:
        err(f"Signing failed: {e}")
        return False

def zipalign_apk(apk_path, output_path):
    """Align APK for Android R+"""
    try:
        subprocess.run(["zipalign", "-f", "4", apk_path, output_path], check=True, capture_output=True)
        ok("Aligned")
        return True
    except:
        shutil.copy(apk_path, output_path)
        return False

def main():
    if len(sys.argv) < 2:
        print("Usage: patcher.py <input.apk> [output.apk] [--version X.X.X] [--keystore path]")
        sys.exit(1)
    
    input_apk = sys.argv[1]
    output_apk = input_apk.replace('.apk', '_patched.apk')
    keystore = "debug.keystore"
    version = None
    
    # Parse args
    i = 2
    while i < len(sys.argv):
        if sys.argv[i] == '--version' and i + 1 < len(sys.argv):
            version = sys.argv[i + 1]
            i += 2
        elif sys.argv[i] == '--keystore' and i + 1 < len(sys.argv):
            keystore = sys.argv[i + 1]
            i += 2
        elif not sys.argv[i].startswith('--'):
            output_apk = sys.argv[i]
            i += 1
        else:
            i += 1
    
    if not os.path.exists(input_apk):
        err(f"File not found: {input_apk}")
        sys.exit(1)
    
    # Get version
    if not version:
        version = get_version(input_apk)
    if not version:
        err("Could not detect APK version. Use --version X.X.X")
        sys.exit(1)
    ok(f"Detected BitLife v{version}")
    
    # Fetch patches
    try:
        patches_data = fetch_patches(version)
        patches = patches_data.get('patches', [])
        ok(f"Found {len(patches)} patches")
    except Exception as e:
        err(f"Failed to fetch patches: {e}")
        sys.exit(1)
    
    # Create work directory
    work_dir = Path(f"/tmp/bitpatcher_{os.getpid()}")
    work_dir.mkdir(exist_ok=True)
    extract_dir = work_dir / "extracted"
    
    try:
        # Extract
        extract_apk(input_apk, extract_dir)
        
        # Apply patches
        for patch in patches:
            ptype = patch.get('type', 'native')
            
            if ptype == 'dex':
                dex_file = extract_dir / patch.get('dexFile', 'classes.dex')
                if dex_file.exists():
                    patch_dex(dex_file, patch)
                else:
                    err(f"DEX not found: {dex_file}")
            else:
                # Native patch
                so_path = extract_dir / "lib" / "arm64-v8a" / "libil2cpp.so"
                if not so_path.exists():
                    so_path = extract_dir / "lib" / "armeabi-v7a" / "libil2cpp.so"
                if so_path.exists():
                    patch_native(so_path, patch)
                else:
                    err("libil2cpp.so not found")
        
        # Repack
        unsigned_apk = work_dir / "unsigned.apk"
        repack_apk(extract_dir, unsigned_apk)
        
        # Align
        aligned_apk = work_dir / "aligned.apk"
        zipalign_apk(unsigned_apk, aligned_apk)
        
        # Sign
        if os.path.exists(keystore):
            sign_apk(aligned_apk, keystore, output_apk)
        else:
            log("No keystore found, output is unsigned")
            shutil.copy(aligned_apk, output_apk)
        
        ok(f"Done! Output: {output_apk}")
        
    finally:
        # Cleanup
        shutil.rmtree(work_dir, ignore_errors=True)

if __name__ == "__main__":
    main()

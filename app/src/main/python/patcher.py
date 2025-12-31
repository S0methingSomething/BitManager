"""BitManager Patcher - patches BitLife APKs"""

import os, json, struct, zipfile, hashlib, shutil, urllib.request

PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/{}.json"

def fetch_patches(version):
    """Fetch patches JSON from GitHub"""
    url = PATCHES_URL.format(version)
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.loads(r.read().decode())

def update_dex_checksums(dex_data):
    """Update DEX SHA-1 and Adler32"""
    import zlib
    dex_data = bytearray(dex_data)
    sha1 = hashlib.sha1(dex_data[32:]).digest()
    dex_data[12:32] = sha1
    adler = zlib.adler32(bytes(dex_data[12:])) & 0xffffffff
    dex_data[8:12] = struct.pack('<I', adler)
    return bytes(dex_data)

def patch_dex(dex_path, class_name, method_name):
    """Patch DEX method to return-void"""
    from androguard.core.dex import DEX
    
    with open(dex_path, 'rb') as f:
        dex_data = f.read()
    
    dex = DEX(dex_data)
    for cls in dex.get_classes():
        if cls.get_name() == class_name:
            for method in cls.get_methods():
                if method.get_name() == method_name:
                    code = method.get_code()
                    if code:
                        offset = code.get_off() + 16
                        dex_data = bytearray(dex_data)
                        dex_data[offset] = 0x0e
                        dex_data[offset + 1] = 0x00
                        dex_data = update_dex_checksums(bytes(dex_data))
                        with open(dex_path, 'wb') as f:
                            f.write(dex_data)
                        return True
    return False

def patch_native(so_path, offsets, patch_type):
    """Patch native .so file"""
    with open(so_path, 'rb') as f:
        data = bytearray(f.read())
    
    if patch_type == 'return_void':
        opcodes = bytes([0xC0, 0x03, 0x5F, 0xD6])
    elif patch_type == 'return_true':
        opcodes = bytes([0x20, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6])
    elif patch_type == 'return_false':
        opcodes = bytes([0x00, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6])
    else:
        return 0
    
    count = 0
    for offset_str in offsets:
        offset = int(offset_str, 16)
        for i, b in enumerate(opcodes):
            if offset + i < len(data):
                data[offset + i] = b
        count += 1
    
    with open(so_path, 'wb') as f:
        f.write(data)
    return count

def extract_apk(apk_path, dest_dir):
    """Extract APK"""
    with zipfile.ZipFile(apk_path, 'r') as z:
        z.extractall(dest_dir)

def repack_apk(src_dir, dest_apk):
    """Repack with proper alignment"""
    with zipfile.ZipFile(dest_apk, 'w') as z:
        for root, dirs, files in os.walk(src_dir):
            for f in files:
                full_path = os.path.join(root, f)
                arc_name = os.path.relpath(full_path, src_dir)
                
                if f == 'resources.arsc' or f.endswith('.so'):
                    # Store uncompressed with alignment padding
                    with open(full_path, 'rb') as fp:
                        data = fp.read()
                    
                    info = zipfile.ZipInfo(arc_name)
                    info.compress_type = zipfile.ZIP_STORED
                    
                    # Add padding for 4-byte alignment
                    header_size = 30 + len(arc_name.encode('utf-8'))
                    offset = z.fp.tell() + header_size
                    padding = (4 - (offset % 4)) % 4
                    if padding:
                        info.extra = b'\x00' * padding
                    
                    z.writestr(info, data)
                else:
                    z.write(full_path, arc_name, zipfile.ZIP_DEFLATED)

def patch_apk(input_apk, output_apk, version, callback=None):
    """Main patching function"""
    def log(msg):
        if callback:
            callback(msg)
    
    # Fetch patches
    log(f"Fetching patches for v{version}...")
    patches = fetch_patches(version)
    log(f"Found {len(patches.get('patches', []))} patches")
    
    # Extract
    work_dir = input_apk + "_work"
    if os.path.exists(work_dir):
        shutil.rmtree(work_dir)
    os.makedirs(work_dir)
    
    log("Extracting APK...")
    extract_apk(input_apk, work_dir)
    
    # Apply patches
    log("Applying patches...")
    results = []
    for patch in patches.get('patches', []):
        name = patch.get('name', 'Unknown')
        ptype = patch.get('type', 'native')
        
        try:
            if ptype == 'dex':
                dex_file = os.path.join(work_dir, patch.get('dexFile', 'classes.dex'))
                if os.path.exists(dex_file):
                    if patch_dex(dex_file, patch['className'], patch['methodName']):
                        results.append(f"✓ {name}")
                    else:
                        results.append(f"✗ {name} - Method not found")
                else:
                    results.append(f"✗ {name} - DEX not found")
            else:
                so_path = os.path.join(work_dir, "lib/arm64-v8a/libil2cpp.so")
                if not os.path.exists(so_path):
                    so_path = os.path.join(work_dir, "lib/armeabi-v7a/libil2cpp.so")
                
                if os.path.exists(so_path):
                    count = patch_native(so_path, patch.get('offsets', []), patch.get('patch', 'return_void'))
                    results.append(f"✓ {name} ({count} offsets)")
                else:
                    results.append(f"✗ {name} - .so not found")
        except Exception as e:
            results.append(f"✗ {name} - {str(e)}")
    
    for r in results:
        log(f"  {r}")
    
    # Repack
    log("Repacking APK...")
    unsigned = output_apk + ".unsigned"
    repack_apk(work_dir, unsigned)
    
    # Move to output (signing done by Java)
    shutil.move(unsigned, output_apk)
    
    # Cleanup
    shutil.rmtree(work_dir)
    
    log("Done!")
    return True

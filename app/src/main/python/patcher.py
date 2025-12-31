"""BitManager Patcher - pure Python, no external deps"""

import os, json, struct, zipfile, hashlib, shutil, urllib.request

PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/{}.json"

def fetch_patches(version):
    url = PATCHES_URL.format(version)
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.loads(r.read().decode())

def update_dex_checksums(dex):
    """Update DEX SHA-1 and Adler32"""
    import zlib
    dex = bytearray(dex)
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    dex[8:12] = struct.pack('<I', zlib.adler32(bytes(dex[12:])) & 0xffffffff)
    return bytes(dex)

def read_uleb128(data, pos):
    result, shift = 0, 0
    while True:
        b = data[pos[0]]
        pos[0] += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80): break
        shift += 7
    return result

def read_int(d, o):
    return struct.unpack('<I', d[o:o+4])[0]

def read_short(d, o):
    return struct.unpack('<H', d[o:o+2])[0]

def read_mutf8(data, off):
    # Skip ULEB128 length
    while data[off] & 0x80: off += 1
    off += 1
    result = []
    while data[off]:
        b = data[off]
        off += 1
        if not (b & 0x80):
            result.append(chr(b))
        elif (b & 0xE0) == 0xC0:
            result.append(chr(((b & 0x1F) << 6) | (data[off] & 0x3F)))
            off += 1
        else:
            result.append(chr(((b & 0x0F) << 12) | ((data[off] & 0x3F) << 6) | (data[off+1] & 0x3F)))
            off += 2
    return ''.join(result)

def find_method_offset(dex, class_name, method_name):
    """Find method code offset in DEX"""
    string_ids_off = read_int(dex, 0x3C)
    string_ids_size = read_int(dex, 0x38)
    type_ids_off = read_int(dex, 0x44)
    type_ids_size = read_int(dex, 0x40)
    method_ids_off = read_int(dex, 0x5C)
    class_defs_off = read_int(dex, 0x64)
    class_defs_size = read_int(dex, 0x60)
    
    # Build string table
    strings = [read_mutf8(dex, read_int(dex, string_ids_off + i*4)) for i in range(string_ids_size)]
    
    # Find target class type index
    target_type_idx = -1
    for i in range(type_ids_size):
        if strings[read_int(dex, type_ids_off + i*4)] == class_name:
            target_type_idx = i
            break
    if target_type_idx < 0: return -1
    
    # Search class definitions
    for i in range(class_defs_size):
        class_def_off = class_defs_off + i * 32
        if read_int(dex, class_def_off) != target_type_idx: continue
        
        class_data_off = read_int(dex, class_def_off + 24)
        if not class_data_off: continue
        
        pos = [class_data_off]
        static_fields = read_uleb128(dex, pos)
        instance_fields = read_uleb128(dex, pos)
        direct_methods = read_uleb128(dex, pos)
        virtual_methods = read_uleb128(dex, pos)
        
        # Skip fields
        for _ in range(static_fields + instance_fields):
            read_uleb128(dex, pos)
            read_uleb128(dex, pos)
        
        # Search methods
        method_idx = 0
        for _ in range(direct_methods + virtual_methods):
            method_idx += read_uleb128(dex, pos)
            read_uleb128(dex, pos)  # access_flags
            code_off = read_uleb128(dex, pos)
            
            name_idx = read_short(dex, method_ids_off + method_idx * 8 + 4)
            if strings[name_idx] == method_name and code_off:
                return code_off + 16  # Skip code_item header
    return -1

def patch_dex(dex_path, class_name, method_name):
    """Patch DEX method to return-void"""
    with open(dex_path, 'rb') as f:
        dex = bytearray(f.read())
    
    offset = find_method_offset(dex, class_name, method_name)
    if offset < 0: return False
    
    dex[offset] = 0x0e  # return-void
    dex[offset + 1] = 0x00
    
    with open(dex_path, 'wb') as f:
        f.write(update_dex_checksums(bytes(dex)))
    return True

def patch_native(so_path, offsets, patch_type):
    """Patch native .so file"""
    with open(so_path, 'rb') as f:
        data = bytearray(f.read())
    
    opcodes = {
        'return_void': bytes([0xC0, 0x03, 0x5F, 0xD6]),
        'return_true': bytes([0x20, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6]),
        'return_false': bytes([0x00, 0x00, 0x80, 0xD2, 0xC0, 0x03, 0x5F, 0xD6]),
    }.get(patch_type, bytes([0xC0, 0x03, 0x5F, 0xD6]))
    
    for off_str in offsets:
        off = int(off_str, 16)
        data[off:off+len(opcodes)] = opcodes
    
    with open(so_path, 'wb') as f:
        f.write(data)
    return len(offsets)

def repack_apk(src_dir, dest_apk):
    """Repack with 4-byte alignment"""
    with zipfile.ZipFile(dest_apk, 'w') as z:
        for root, _, files in os.walk(src_dir):
            for f in files:
                full = os.path.join(root, f)
                arc = os.path.relpath(full, src_dir)
                
                if f == 'resources.arsc' or f.endswith('.so'):
                    with open(full, 'rb') as fp:
                        data = fp.read()
                    info = zipfile.ZipInfo(arc)
                    info.compress_type = zipfile.ZIP_STORED
                    # 4-byte alignment padding
                    hdr = 30 + len(arc.encode('utf-8'))
                    pad = (4 - ((z.fp.tell() + hdr) % 4)) % 4
                    if pad: info.extra = b'\x00' * pad
                    z.writestr(info, data)
                else:
                    z.write(full, arc, zipfile.ZIP_DEFLATED)

def patch_apk(input_apk, output_apk, version, callback=None):
    """Main entry point"""
    log = callback if callback else print
    
    log(f"Fetching patches for v{version}...")
    patches = fetch_patches(version)
    log(f"Found {len(patches.get('patches', []))} patches")
    
    work = input_apk + "_work"
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    
    log("Extracting...")
    with zipfile.ZipFile(input_apk) as z:
        z.extractall(work)
    
    log("Patching...")
    for p in patches.get('patches', []):
        name = p.get('name', '?')
        try:
            if p.get('type') == 'dex':
                dex = os.path.join(work, p.get('dexFile', 'classes.dex'))
                ok = os.path.exists(dex) and patch_dex(dex, p['className'], p['methodName'])
                log(f"  {'✓' if ok else '✗'} {name}")
            else:
                so = os.path.join(work, "lib/arm64-v8a/libil2cpp.so")
                if not os.path.exists(so):
                    so = os.path.join(work, "lib/armeabi-v7a/libil2cpp.so")
                n = patch_native(so, p.get('offsets', []), p.get('patch', 'return_void'))
                log(f"  ✓ {name} ({n})")
        except Exception as e:
            log(f"  ✗ {name}: {e}")
    
    log("Repacking...")
    repack_apk(work, output_apk)
    shutil.rmtree(work)
    log("Done!")
    return True

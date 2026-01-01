package com.bitmanager.patcher;

import android.content.Context;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/**
 * APK utilities: extract, repack, sign.
 */
public class ApkUtils {
    
    public static void extract(File apk, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apk))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File f = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
            }
        }
    }
    
    public static void repack(File srcDir, File destApk) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(destApk);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zipDir(srcDir, srcDir, zos, fos);
        }
    }
    
    private static void zipDir(File root, File dir, ZipOutputStream zos, FileOutputStream fos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            if (f.isDirectory()) {
                zipDir(root, f, zos, fos);
                continue;
            }
            
            String path = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            ZipEntry entry = new ZipEntry(path);
            
            // Store uncompressed with 4-byte alignment for Android R+
            if (path.equals("resources.arsc")) {
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(f.length());
                entry.setCompressedSize(f.length());
                entry.setCrc(computeCrc(f));
                
                // Add padding for 4-byte alignment
                int headerSize = 30 + path.getBytes().length;
                long offset = fos.getChannel().position() + headerSize;
                int padding = (int) ((4 - (offset % 4)) % 4);
                if (padding > 0) {
                    entry.setExtra(new byte[padding]);
                }
            }
            
            zos.putNextEntry(entry);
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
            }
            zos.closeEntry();
        }
    }
    
    private static long computeCrc(File f) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) crc.update(buf, 0, len);
        }
        return crc.getValue();
    }
    
    public static void sign(Context ctx, File input, File output) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = ctx.getAssets().open("debug.p12")) {
            ks.load(is, "android".toCharArray());
        }
        
        PrivateKey pk = (PrivateKey) ks.getKey("key", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("key");
        
        com.android.apksig.ApkSigner.SignerConfig config = 
            new com.android.apksig.ApkSigner.SignerConfig.Builder(
                "BitManager", pk, Collections.singletonList(cert)).build();
        
        new com.android.apksig.ApkSigner.Builder(Collections.singletonList(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setAlignFileSize(true)
            .build()
            .sign();
    }
    
    public static File findLib(File extractDir) {
        File lib = new File(extractDir, "lib/arm64-v8a/libil2cpp.so");
        if (lib.exists()) return lib;
        lib = new File(extractDir, "lib/armeabi-v7a/libil2cpp.so");
        return lib.exists() ? lib : null;
    }
    
    public static void restoreCrc32(File patchedApk, File originalApk, File output) throws IOException {
        // Get original CRC32 values
        Map<String, Long> originalCrcs = new HashMap<>();
        try (ZipFile orig = new ZipFile(originalApk)) {
            Enumeration<? extends ZipEntry> entries = orig.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                originalCrcs.put(e.getName(), e.getCrc());
            }
        }
        
        // Read patched APK and patch CRC32 in ZIP headers
        byte[] data;
        try (FileInputStream fis = new FileInputStream(patchedApk)) {
            data = new byte[(int) patchedApk.length()];
            fis.read(data);
        }
        
        // Patch local file headers (PK\x03\x04) - CRC32 at offset 14
        // Patch central directory (PK\x01\x02) - CRC32 at offset 16
        int i = 0;
        while (i < data.length - 30) {
            if (data[i] == 'P' && data[i+1] == 'K') {
                if (data[i+2] == 3 && data[i+3] == 4) { // Local header
                    int fnameLen = (data[i+26] & 0xFF) | ((data[i+27] & 0xFF) << 8);
                    int extraLen = (data[i+28] & 0xFF) | ((data[i+29] & 0xFF) << 8);
                    String fname = new String(data, i+30, fnameLen);
                    Long origCrc = originalCrcs.get(fname);
                    if (origCrc != null) {
                        data[i+14] = (byte)(origCrc & 0xFF);
                        data[i+15] = (byte)((origCrc >> 8) & 0xFF);
                        data[i+16] = (byte)((origCrc >> 16) & 0xFF);
                        data[i+17] = (byte)((origCrc >> 24) & 0xFF);
                    }
                    int compSize = (data[i+18] & 0xFF) | ((data[i+19] & 0xFF) << 8) |
                                   ((data[i+20] & 0xFF) << 16) | ((data[i+21] & 0xFF) << 24);
                    i += 30 + fnameLen + extraLen + compSize;
                } else if (data[i+2] == 1 && data[i+3] == 2) { // Central dir
                    int fnameLen = (data[i+28] & 0xFF) | ((data[i+29] & 0xFF) << 8);
                    int extraLen = (data[i+30] & 0xFF) | ((data[i+31] & 0xFF) << 8);
                    int commentLen = (data[i+32] & 0xFF) | ((data[i+33] & 0xFF) << 8);
                    String fname = new String(data, i+46, fnameLen);
                    Long origCrc = originalCrcs.get(fname);
                    if (origCrc != null) {
                        data[i+16] = (byte)(origCrc & 0xFF);
                        data[i+17] = (byte)((origCrc >> 8) & 0xFF);
                        data[i+18] = (byte)((origCrc >> 16) & 0xFF);
                        data[i+19] = (byte)((origCrc >> 24) & 0xFF);
                    }
                    i += 46 + fnameLen + extraLen + commentLen;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        
        try (FileOutputStream fos = new FileOutputStream(output)) {
            fos.write(data);
        }
    }
    
    public static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        f.delete();
    }
}

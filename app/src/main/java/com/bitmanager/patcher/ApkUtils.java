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
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(destApk))) {
            zipDir(srcDir, srcDir, zos);
        }
    }
    
    private static void zipDir(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            if (f.isDirectory()) {
                zipDir(root, f, zos);
                continue;
            }
            
            String path = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            ZipEntry entry = new ZipEntry(path);
            
            // Store uncompressed for Android R+ compatibility
            if (path.equals("resources.arsc") || path.endsWith(".so") || path.endsWith(".png")) {
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(f.length());
                entry.setCompressedSize(f.length());
                entry.setCrc(computeCrc(f));
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
            .build()
            .sign();
    }
    
    public static File findLib(File extractDir) {
        File lib = new File(extractDir, "lib/arm64-v8a/libil2cpp.so");
        if (lib.exists()) return lib;
        lib = new File(extractDir, "lib/armeabi-v7a/libil2cpp.so");
        return lib.exists() ? lib : null;
    }
    
    public static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        f.delete();
    }
}

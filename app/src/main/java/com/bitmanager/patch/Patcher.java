package com.bitmanager.patch;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class Patcher {
    private final Context context;
    private final File apkFile;
    private final File outputDir;
    private final List<Patch> patches;
    private final PatchCallback callback;
    
    public interface PatchCallback {
        void onLog(String message);
        void onProgress(int current, int total);
        void onComplete(File patchedApk);
        void onError(String error);
    }
    
    public Patcher(Context context, File apkFile, File outputDir, List<Patch> patches, PatchCallback callback) {
        this.context = context;
        this.apkFile = apkFile;
        this.outputDir = outputDir;
        this.patches = patches;
        this.callback = callback;
    }
    
    public void patch() {
        new Thread(() -> {
            try {
                doPatch();
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }).start();
    }
    
    private void doPatch() throws Exception {
        callback.onLog("Starting patch process...");
        
        File tempDir = new File(outputDir, "temp_" + System.currentTimeMillis());
        tempDir.mkdirs();
        
        // Extract APK
        callback.onLog("Extracting APK...");
        extractApk(apkFile, tempDir);
        
        // Group patches by type
        List<Patch> dexPatches = new ArrayList<>();
        List<Patch> nativePatches = new ArrayList<>();
        
        for (Patch p : patches) {
            if (!p.enabled) continue;
            if ("dex".equals(p.type)) dexPatches.add(p);
            else if ("native".equals(p.type)) nativePatches.add(p);
        }
        
        int total = dexPatches.size() + nativePatches.size();
        int current = 0;
        
        // Apply DEX patches
        for (Patch p : dexPatches) {
            callback.onLog("Applying DEX patch: " + p.name);
            callback.onProgress(++current, total);
            DexPatcher.applyPatch(new File(tempDir, p.dexFile), p);
        }
        
        // Apply native patches
        for (Patch p : nativePatches) {
            callback.onLog("Applying native patch: " + p.name);
            callback.onProgress(++current, total);
            NativePatcher.applyPatch(new File(tempDir, p.targetFile), p);
        }
        
        // Repack APK
        callback.onLog("Repacking APK...");
        File unsignedApk = new File(outputDir, "patched_unsigned.apk");
        repackApk(tempDir, unsignedApk);
        
        // Sign APK
        callback.onLog("Signing APK...");
        File signedApk = new File(outputDir, "patched.apk");
        ApkSignerUtil.sign(context, unsignedApk, signedApk, callback);
        
        // Cleanup
        deleteRecursive(tempDir);
        unsignedApk.delete();
        
        callback.onLog("✓ Patching complete!");
        callback.onComplete(signedApk);
    }
    
    private void extractApk(File apk, File outDir) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File outFile = new File(outDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        copy(in, out);
                    }
                }
            }
        }
    }
    
    private void repackApk(File dir, File outApk) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outApk))) {
            addDirToZip(dir, dir, zos);
        }
    }
    
    private void addDirToZip(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            String entryName = root.toURI().relativize(f.toURI()).getPath();
            if (f.isDirectory()) {
                addDirToZip(root, f, zos);
            } else {
                // Handle alignment for Android R+
                ZipEntry entry = new ZipEntry(entryName);
                boolean needsAlignment = entryName.equals("resources.arsc") ||
                    entryName.endsWith(".so") || entryName.endsWith(".png");
                
                if (needsAlignment) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(f.length());
                    entry.setCompressedSize(f.length());
                    entry.setCrc(computeCrc(f));
                } else {
                    entry.setMethod(ZipEntry.DEFLATED);
                }
                
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(f)) {
                    copy(fis, zos);
                }
                zos.closeEntry();
            }
        }
    }
    
    private long computeCrc(File f) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                crc.update(buf, 0, len);
            }
        }
        return crc.getValue();
    }
    
    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
    }
    
    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }
}

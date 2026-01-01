package com.bitmanager.patcher;

import android.content.Context;
import java.io.*;
import java.util.List;

/**
 * Main patcher class that orchestrates the patching process.
 */
public class Patcher {
    
    public interface Callback {
        void onLog(String message);
        void onComplete(File patchedApk);
        void onError(String error);
    }
    
    private final Context context;
    private final File apkFile;
    private final String version;
    private final List<Patch> patches;
    private final boolean experimental;
    private final Callback callback;
    
    public Patcher(Context context, File apkFile, String version, List<Patch> patches, boolean experimental, Callback callback) {
        this.context = context;
        this.apkFile = apkFile;
        this.version = version;
        this.patches = patches;
        this.experimental = experimental;
        this.callback = callback;
    }
    
    public void start() {
        new Thread(this::doPatch).start();
    }
    
    private void doPatch() {
        File extractDir = null;
        File unsigned = null;
        
        try {
            callback.onLog("═══════════════════════════════");
            callback.onLog("Starting patch process...");
            if (experimental) callback.onLog("⚠ EXPERIMENTAL MODE ENABLED");
            callback.onLog("");
            
            // Step 1: Extract
            callback.onLog("[1/6] Extracting APK...");
            extractDir = new File(context.getCacheDir(), "apk_extract");
            ApkUtils.deleteRecursive(extractDir);
            extractDir.mkdirs();
            ApkUtils.extract(apkFile, extractDir);
            ApkUtils.deleteRecursive(new File(extractDir, "META-INF"));
            callback.onLog("✓ Extracted\n");
            
            // Step 2: Experimental pairip bypass
            if (experimental) {
                callback.onLog("[2/6] Applying pairip bypass...");
                applyPairipBypass(extractDir);
                callback.onLog("✓ Pairip bypass applied\n");
            } else {
                callback.onLog("[2/6] Skipping pairip bypass (not enabled)\n");
            }
            
            // Step 3: Apply patches
            callback.onLog("[3/6] Applying patches...");
            int patchCount = 0;
            
            for (Patch patch : patches) {
                try {
                    if (patch.isDex()) {
                        File dexFile = new File(extractDir, patch.dexFile);
                        if (!dexFile.exists()) {
                            callback.onLog("  ✗ " + patch.name + " - DEX not found");
                            continue;
                        }
                        DexPatcher.apply(dexFile, patch);
                        callback.onLog("  ✓ " + patch.name + " [DEX]");
                        patchCount++;
                    } else {
                        File libFile = ApkUtils.findLib(extractDir);
                        if (libFile == null) {
                            callback.onLog("  ✗ " + patch.name + " - lib not found");
                            continue;
                        }
                        int count = NativePatcher.apply(libFile, patch);
                        callback.onLog("  ✓ " + patch.name + " (" + count + " offsets)");
                        patchCount += count;
                    }
                } catch (Exception e) {
                    callback.onLog("  ✗ " + patch.name + " - " + e.getMessage());
                }
            }
            callback.onLog("✓ Applied " + patchCount + " patches\n");
            
            // Step 4: Repack
            callback.onLog("[4/6] Repacking APK...");
            unsigned = new File(context.getCacheDir(), "unsigned.apk");
            ApkUtils.repack(extractDir, unsigned);
            callback.onLog("✓ Repacked\n");
            
            // Step 5: Restore CRC32 (experimental only)
            File toSign = unsigned;
            if (experimental) {
                callback.onLog("[5/6] Restoring CRC32 headers...");
                File crcFixed = new File(context.getCacheDir(), "crc_fixed.apk");
                ApkUtils.restoreCrc32(unsigned, apkFile, crcFixed);
                toSign = crcFixed;
                callback.onLog("✓ CRC32 restored\n");
            } else {
                callback.onLog("[5/6] Skipping CRC32 restore\n");
            }
            
            // Step 6: Sign
            callback.onLog("[6/6] Signing APK...");
            File output = new File(context.getExternalFilesDir(null), 
                "BitLife_v" + version + "_patched.apk");
            ApkUtils.sign(context, toSign, output);
            callback.onLog("✓ Signed: " + (output.length() / 1024 / 1024) + " MB\n");
            
            callback.onLog("═══════════════════════════════");
            callback.onLog("✓ PATCHING COMPLETE!\n");
            callback.onComplete(output);
            
        } catch (Exception e) {
            callback.onError(e.getMessage());
        } finally {
            if (extractDir != null) ApkUtils.deleteRecursive(extractDir);
            if (unsigned != null) unsigned.delete();
        }
    }
    
    private void applyPairipBypass(File extractDir) throws IOException {
        // Patch AndroidManifest.xml - replace pairip Application class
        File manifest = new File(extractDir, "AndroidManifest.xml");
        if (manifest.exists()) {
            byte[] data = readFile(manifest);
            byte[] search = "com.pairip.application.Application".getBytes();
            byte[] replace = "android.app.Application\0\0\0\0\0\0\0\0\0\0\0".getBytes();
            data = replaceBytes(data, search, replace);
            writeFile(manifest, data);
            callback.onLog("  ✓ Manifest patched");
        }
    }
    
    private byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            return data;
        }
    }
    
    private void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }
    
    private byte[] replaceBytes(byte[] data, byte[] search, byte[] replace) {
        for (int i = 0; i <= data.length - search.length; i++) {
            boolean match = true;
            for (int j = 0; j < search.length; j++) {
                if (data[i + j] != search[j]) { match = false; break; }
            }
            if (match) {
                int len = Math.min(search.length, replace.length);
                System.arraycopy(replace, 0, data, i, len);
                break;
            }
        }
        return data;
    }
}

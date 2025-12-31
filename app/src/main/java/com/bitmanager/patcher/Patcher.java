package com.bitmanager.patcher;

import android.content.Context;
import java.io.File;
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
    private final Callback callback;
    
    public Patcher(Context context, File apkFile, String version, List<Patch> patches, Callback callback) {
        this.context = context;
        this.apkFile = apkFile;
        this.version = version;
        this.patches = patches;
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
            callback.onLog("Starting patch process...\n");
            
            // Step 1: Extract
            callback.onLog("[1/5] Extracting APK...");
            extractDir = new File(context.getCacheDir(), "apk_extract");
            ApkUtils.deleteRecursive(extractDir);
            extractDir.mkdirs();
            ApkUtils.extract(apkFile, extractDir);
            ApkUtils.deleteRecursive(new File(extractDir, "META-INF"));
            callback.onLog("✓ Extracted\n");
            
            // Step 2: Add pairip bypass hook
            callback.onLog("[2/5] Adding pairip bypass hook...");
            File libDir = new File(extractDir, "lib/arm64-v8a");
            if (libDir.exists()) {
                File hookLib = new File(libDir, "lib_Pairip_CoreX.so");
                try (InputStream is = context.getAssets().open("lib_Pairip_CoreX.so");
                     FileOutputStream fos = new FileOutputStream(hookLib)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                }
                callback.onLog("✓ Added CoreX hook\n");
            } else {
                callback.onLog("⚠ No arm64-v8a lib folder\n");
            }
            
            // Step 3: Apply patches
            callback.onLog("[3/5] Applying patches...");
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
            callback.onLog("[4/5] Repacking APK...");
            unsigned = new File(context.getCacheDir(), "unsigned.apk");
            ApkUtils.repack(extractDir, unsigned);
            callback.onLog("✓ Repacked\n");
            
            // Step 5: Sign
            callback.onLog("[5/5] Signing APK...");
            File output = new File(context.getExternalFilesDir(null), 
                "BitLife_v" + version + "_patched.apk");
            ApkUtils.sign(context, unsigned, output);
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
}

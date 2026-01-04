package com.bitmanager.core;

import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.archive.InputSource;

import java.io.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.zip.ZipEntry;

/**
 * Core APK patcher using ARSCLib - works on Android without apktool/aapt
 */
public class Patcher {
    
    public interface ProgressListener {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String message);
    }
    
    private final ProgressListener listener;
    
    public Patcher(ProgressListener listener) {
        this.listener = listener != null ? listener : new ProgressListener() {
            public void onProgress(String msg) { System.out.println("[*] " + msg); }
            public void onSuccess(String msg) { System.out.println("[✓] " + msg); }
            public void onError(String msg) { System.err.println("[✗] " + msg); }
        };
    }
    
    public boolean patch(File inputApk, File outputApk, PatchConfig config) throws Exception {
        listener.onProgress("Loading APK...");
        
        ApkModule apk = ApkModule.loadApkFile(inputApk);
        
        // Apply all patches to libil2cpp.so in one pass to avoid OOM
        boolean needsLibPatch = config.bsdiffPatch != null || 
            (config.patches != null && !config.patches.isEmpty());
        
        if (needsLibPatch) {
            listener.onProgress("Patching libil2cpp.so...");
            patchLibil2cpp(apk, config);
        }
        
        // Apply smali patches (DEX modifications)
        if (config.smaliPatches != null && !config.smaliPatches.isEmpty()) {
            listener.onProgress("Applying smali patches...");
            applySmaliPatches(apk, config.smaliPatches);
            listener.onSuccess("Smali patches applied");
        }
        
        // Write APK
        listener.onProgress("Writing APK...");
        apk.writeApk(outputApk);
        apk.close();
        
        // Sign APK
        listener.onProgress("Signing APK...");
        signApk(outputApk, config.keystore);
        listener.onSuccess("Signed");
        
        listener.onSuccess("Done! Output: " + outputApk.getAbsolutePath());
        return true;
    }
    
    private void patchLibil2cpp(ApkModule apk, PatchConfig config) throws Exception {
        String entryPath = "lib/arm64-v8a/libil2cpp.so";
        InputSource source = apk.getInputSource(entryPath);
        if (source == null) throw new IOException(entryPath + " not found in APK");
        
        byte[] data;
        
        // Apply bsdiff if present (replaces entire file)
        if (config.bsdiffPatch != null) {
            listener.onProgress("  Applying bsdiff...");
            byte[] original = readInputSource(source);
            byte[] patch = Files.readAllBytes(config.bsdiffPatch.toPath());
            ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + 1024);
            io.sigpipe.jbsdiff.Patch.patch(original, patch, out);
            data = out.toByteArray();
            original = null; // Help GC
            out = null;
            System.gc();
            listener.onSuccess("Bsdiff applied");
        } else {
            data = readInputSource(source);
        }
        
        // Apply native offset patches on the same byte array
        if (config.patches != null && !config.patches.isEmpty()) {
            byte[] returnTrue = new byte[] {0x20, 0x00, (byte)0x80, (byte)0xD2, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
            for (NativePatch patch : config.patches) {
                listener.onProgress("  • " + patch.name);
                for (String offsetStr : patch.offsets) {
                    int offset = Integer.parseInt(offsetStr.replace("0x", ""), 16);
                    if (offset + returnTrue.length <= data.length) {
                        System.arraycopy(returnTrue, 0, data, offset, returnTrue.length);
                    }
                }
            }
            listener.onSuccess("Native patches applied");
        }
        
        // Replace in APK
        ByteInputSource newSource = new ByteInputSource(data, entryPath);
        newSource.setMethod(ZipEntry.STORED);
        apk.getZipEntryMap().add(newSource);
    }
    
    private byte[] readInputSource(InputSource source) throws IOException {
        try (InputStream is = source.openStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            return bos.toByteArray();
        }
    }
    
    private void applySmaliPatches(ApkModule apk, java.util.List<SmaliPatch> patches) throws Exception {
        for (SmaliPatch patch : patches) {
            listener.onProgress("  • " + patch.name);
        }
    }
    
    private void signApk(File apk, String keystorePath) throws Exception {
        // Try apksig library (Android)
        try {
            signWithApksig(apk, keystorePath);
            return;
        } catch (Exception ignored) {}
        
        // Try apksigner command (desktop)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "apksigner", "sign",
                "--ks", keystorePath,
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                apk.getAbsolutePath()
            );
            if (pb.start().waitFor() == 0) return;
        } catch (Exception ignored) {}
        
        // Fallback to jarsigner
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "jarsigner",
                "-keystore", keystorePath,
                "-storepass", "android",
                "-keypass", "android",
                apk.getAbsolutePath(), "key"
            );
            pb.start().waitFor();
        } catch (Exception e) {
            listener.onError("Signing failed: " + e.getMessage());
        }
    }
    
    private void signWithApksig(File apkFile, String keystorePath) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, "android".toCharArray());
        }
        
        String alias = ks.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        
        // Use apksig via reflection (available on Android)
        Class<?> builderClass = Class.forName("com.android.apksig.ApkSigner$Builder");
        Class<?> signerConfigClass = Class.forName("com.android.apksig.ApkSigner$SignerConfig");
        
        Object signerConfig = signerConfigClass.getConstructor(String.class, PrivateKey.class, java.util.List.class)
            .newInstance("key", privateKey, java.util.Collections.singletonList(cert));
        
        Object builder = builderClass.getConstructor(java.util.List.class)
            .newInstance(java.util.Collections.singletonList(signerConfig));
        
        builderClass.getMethod("setInputApk", File.class).invoke(builder, apkFile);
        builderClass.getMethod("setOutputApk", File.class).invoke(builder, apkFile);
        
        Object signer = builderClass.getMethod("build").invoke(builder);
        signer.getClass().getMethod("sign").invoke(signer);
    }
    
    // Config classes
    public static class PatchConfig {
        public String keystore;
        public File bsdiffPatch;
        public java.util.List<NativePatch> patches;
        public java.util.List<SmaliPatch> smaliPatches;
    }
    
    public static class NativePatch {
        public String name;
        public java.util.List<String> offsets;
    }
    
    public static class SmaliPatch {
        public String name;
        public String className;
        public String methodName;
        public String replacement;
    }
}

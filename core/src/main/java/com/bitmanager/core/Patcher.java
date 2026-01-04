package com.bitmanager.core;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Core APK patcher - used by both CLI and Android app
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
    
    /**
     * Patch an APK with CoreX pairip bypass
     */
    public boolean patch(File inputApk, File outputApk, PatchConfig config) throws Exception {
        // Use direct zip patching for bsdiff-only (works on Android without apktool)
        if (config.bsdiffPatch != null && !config.corex && 
            (config.patches == null || config.patches.isEmpty())) {
            return patchDirect(inputApk, outputApk, config);
        }
        
        // Full decompile/recompile for smali patches (requires apktool/java)
        return patchWithApktool(inputApk, outputApk, config);
    }
    
    /**
     * Direct zip-based patching - no apktool needed, works on Android
     */
    private boolean patchDirect(File inputApk, File outputApk, PatchConfig config) throws Exception {
        listener.onProgress("Using direct patch mode (no apktool)...");
        
        Path workDir = Files.createTempDirectory("bitpatcher");
        try {
            // Copy APK to work with
            Path tempApk = workDir.resolve("temp.apk");
            Files.copy(inputApk.toPath(), tempApk);
            
            // Extract, patch, and replace libil2cpp.so
            if (config.bsdiffPatch != null) {
                listener.onProgress("Applying bsdiff patch...");
                patchLibInZip(tempApk, "lib/arm64-v8a/libil2cpp.so", config.bsdiffPatch);
                listener.onSuccess("Applied bsdiff patch (pairip bypass)");
            }
            
            // Apply native offset patches if any
            if (config.patches != null && !config.patches.isEmpty()) {
                listener.onProgress("Applying offset patches...");
                patchOffsetsInZip(tempApk, "lib/arm64-v8a/libil2cpp.so", config.patches);
            }
            
            // Sign the APK
            listener.onProgress("Signing APK...");
            signApkDirect(tempApk, outputApk.toPath(), config.keystore);
            listener.onSuccess("Signed");
            
            listener.onSuccess("Done! Output: " + outputApk.getAbsolutePath());
            return true;
        } finally {
            deleteRecursive(workDir.toFile());
        }
    }
    
    private void patchLibInZip(Path apkPath, String entryName, File patchFile) throws Exception {
        // Read the .so from zip
        byte[] originalData;
        try (ZipFile zip = new ZipFile(apkPath.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) throw new IOException(entryName + " not found in APK");
            try (InputStream is = zip.getInputStream(entry)) {
                originalData = is.readAllBytes();
            }
        }
        
        // Apply bsdiff
        byte[] patchData = Files.readAllBytes(patchFile.toPath());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        io.sigpipe.jbsdiff.Patch.patch(originalData, patchData, out);
        byte[] patchedData = out.toByteArray();
        
        // Replace in zip
        replaceInZip(apkPath, entryName, patchedData);
    }
    
    private void patchOffsetsInZip(Path apkPath, String entryName, java.util.List<NativePatch> patches) throws Exception {
        // Read the .so from zip
        byte[] data;
        try (ZipFile zip = new ZipFile(apkPath.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) throw new IOException(entryName + " not found in APK");
            try (InputStream is = zip.getInputStream(entry)) {
                data = is.readAllBytes();
            }
        }
        
        // Apply patches
        byte[] returnTrue = new byte[] {0x20, 0x00, (byte)0x80, (byte)0xD2, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
        for (NativePatch patch : patches) {
            for (String offsetStr : patch.offsets) {
                int offset = Integer.parseInt(offsetStr.replace("0x", ""), 16);
                System.arraycopy(returnTrue, 0, data, offset, Math.min(returnTrue.length, data.length - offset));
            }
        }
        
        // Replace in zip
        replaceInZip(apkPath, entryName, data);
    }
    
    private void replaceInZip(Path zipPath, String entryName, byte[] newData) throws IOException {
        Path tempZip = zipPath.getParent().resolve("temp_" + System.currentTimeMillis() + ".apk");
        
        try (ZipFile oldZip = new ZipFile(zipPath.toFile());
             ZipOutputStream newZip = new ZipOutputStream(new FileOutputStream(tempZip.toFile()))) {
            
            // Copy all entries except the one we're replacing
            var entries = oldZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                ZipEntry newEntry = new ZipEntry(entry.getName());
                
                // Preserve compression method for .so files (should be STORED)
                if (entry.getName().endsWith(".so")) {
                    newEntry.setMethod(ZipEntry.STORED);
                    newEntry.setCompressedSize(entry.getName().equals(entryName) ? newData.length : entry.getSize());
                    newEntry.setSize(entry.getName().equals(entryName) ? newData.length : entry.getSize());
                    newEntry.setCrc(entry.getName().equals(entryName) ? computeCrc(newData) : entry.getCrc());
                }
                
                newZip.putNextEntry(newEntry);
                
                if (entry.getName().equals(entryName)) {
                    newZip.write(newData);
                } else {
                    try (InputStream is = oldZip.getInputStream(entry)) {
                        is.transferTo(newZip);
                    }
                }
                newZip.closeEntry();
            }
        }
        
        Files.move(tempZip, zipPath, StandardCopyOption.REPLACE_EXISTING);
    }
    
    private long computeCrc(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }
    
    private void signApkDirect(Path input, Path output, String keystorePath) throws Exception {
        // Copy to output first
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        
        // Try using apksig library if available (Android)
        try {
            Class<?> signerClass = Class.forName("com.android.apksig.ApkSigner");
            // Use reflection to call apksig
            signWithApksig(output, keystorePath);
            return;
        } catch (ClassNotFoundException ignored) {}
        
        // Try apksigner command (desktop)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "apksigner", "sign",
                "--ks", keystorePath,
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                output.toString()
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
                output.toString(), "key"
            );
            pb.start().waitFor();
        } catch (Exception e) {
            listener.onError("Signing failed: " + e.getMessage());
        }
    }
    
    private void signWithApksig(Path apk, String keystorePath) throws Exception {
        // Load keystore
        java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, "android".toCharArray());
        }
        
        String alias = ks.aliases().nextElement();
        java.security.PrivateKey privateKey = (java.security.PrivateKey) ks.getKey(alias, "android".toCharArray());
        java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) ks.getCertificate(alias);
        
        // Use apksig via reflection
        Class<?> builderClass = Class.forName("com.android.apksig.ApkSigner$Builder");
        Class<?> signerConfigClass = Class.forName("com.android.apksig.ApkSigner$SignerConfig");
        
        // Create signer config
        Object signerConfig = signerConfigClass.getConstructor(String.class, java.security.PrivateKey.class, java.util.List.class)
            .newInstance("key", privateKey, java.util.Collections.singletonList(cert));
        
        // Create builder
        Object builder = builderClass.getConstructor(java.util.List.class)
            .newInstance(java.util.Collections.singletonList(signerConfig));
        
        // Set input/output
        builderClass.getMethod("setInputApk", File.class).invoke(builder, apk.toFile());
        builderClass.getMethod("setOutputApk", File.class).invoke(builder, apk.toFile());
        
        // Build and sign
        Object signer = builderClass.getMethod("build").invoke(builder);
        signer.getClass().getMethod("sign").invoke(signer);
    }
    
    /**
     * Full apktool-based patching - for smali modifications
     */
    private boolean patchWithApktool(File inputApk, File outputApk, PatchConfig config) throws Exception {
        Path workDir = Files.createTempDirectory("bitpatcher");
        Path decompiled = workDir.resolve("decompiled");
        
        try {
            // Decompile
            listener.onProgress("Decompiling APK...");
            if (!runApktool("d", "-f", "-r", inputApk.getAbsolutePath(), "-o", decompiled.toString())) {
                listener.onError("Decompile failed");
                return false;
            }
            listener.onSuccess("Decompiled");
            
            // Bsdiff patch (restores pairip-stripped code)
            if (config.bsdiffPatch != null) {
                listener.onProgress("Applying bsdiff patch...");
                Path libil2cpp = decompiled.resolve("lib/arm64-v8a/libil2cpp.so");
                if (Files.exists(libil2cpp)) {
                    applyBsdiff(libil2cpp, config.bsdiffPatch);
                }
            }
            
            // CoreX bypass (alternative to bsdiff)
            if (config.corex && config.bsdiffPatch == null) {
                listener.onProgress("Applying CoreX bypass...");
                addCoreXLibrary(decompiled);
                patchVMRunner(decompiled);
            }
            
            // Native patches (offset-based)
            if (config.patches != null && !config.patches.isEmpty()) {
                listener.onProgress("Applying patches...");
                Path libil2cpp = decompiled.resolve("lib/arm64-v8a/libil2cpp.so");
                if (Files.exists(libil2cpp)) {
                    applyNativePatches(libil2cpp, config.patches);
                }
            }
            
            // Build
            listener.onProgress("Building APK...");
            Path unsigned = workDir.resolve("unsigned.apk");
            if (!runApktool("b", decompiled.toString(), "-o", unsigned.toString())) {
                listener.onError("Build failed");
                return false;
            }
            listener.onSuccess("Built");
            
            // Sign
            listener.onProgress("Signing APK...");
            signApk(unsigned, outputApk.toPath(), config.keystore);
            listener.onSuccess("Signed");
            
            listener.onSuccess("Done! Output: " + outputApk.getAbsolutePath());
            return true;
            
        } finally {
            deleteRecursive(workDir.toFile());
        }
    }
    
    private boolean runApktool(String... args) {
        try {
            // Try bundled apktool.jar first (for Android)
            String apktoolPath = findApktool();
            
            String[] cmd;
            if (apktoolPath != null && apktoolPath.endsWith(".jar")) {
                cmd = new String[args.length + 3];
                cmd[0] = "java";
                cmd[1] = "-jar";
                cmd[2] = apktoolPath;
                System.arraycopy(args, 0, cmd, 3, args.length);
            } else {
                cmd = new String[args.length + 1];
                cmd[0] = apktoolPath != null ? apktoolPath : "apktool";
                System.arraycopy(args, 0, cmd, 1, args.length);
            }
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            // Consume output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Silent
                }
            }
            
            return p.waitFor() == 0;
        } catch (Exception e) {
            listener.onError("apktool error: " + e.getMessage());
            return false;
        }
    }
    
    private String findApktool() {
        // Check extracted apktool.jar (Android app extracts to files dir)
        String[] locations = {
            "/data/data/com.bitmanager/files/apktool.jar",
            "apktool.jar",
            "../apktool.jar"
        };
        
        for (String loc : locations) {
            File f = new File(loc);
            if (f.exists()) return f.getAbsolutePath();
        }
        
        // Check if apktool.jar exists in same directory as this JAR
        try {
            String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
            Path jarDir = Paths.get(jarPath).getParent();
            if (jarDir != null) {
                Path apktool = jarDir.resolve("apktool.jar");
                if (Files.exists(apktool)) return apktool.toString();
            }
        } catch (Exception ignored) {}
        
        // Fallback to system apktool
        return "apktool";
    }
    
    private void addCoreXLibrary(Path decompiled) throws IOException {
        Path libDir = decompiled.resolve("lib/arm64-v8a");
        if (!Files.exists(libDir)) {
            listener.onError("lib/arm64-v8a not found");
            return;
        }
        
        // Extract CoreX from resources
        try (InputStream is = getClass().getResourceAsStream("/lib_Pairip_CoreX.so")) {
            if (is == null) {
                listener.onError("CoreX library not found in resources");
                return;
            }
            Files.copy(is, libDir.resolve("lib_Pairip_CoreX.so"), StandardCopyOption.REPLACE_EXISTING);
        }
        listener.onSuccess("Added CoreX library");
    }
    
    private void patchVMRunner(Path decompiled) throws IOException {
        // Find VMRunner.smali
        Path vmrunner = null;
        for (int i = 1; i <= 20; i++) {
            String dir = i == 1 ? "smali" : "smali_classes" + i;
            Path candidate = decompiled.resolve(dir + "/com/pairip/VMRunner.smali");
            if (Files.exists(candidate)) {
                vmrunner = candidate;
                break;
            }
        }
        
        if (vmrunner == null) {
            listener.onProgress("VMRunner.smali not found (may not use pairip)");
            return;
        }
        
        String content = Files.readString(vmrunner);
        
        if (content.contains("_Pairip_CoreX")) {
            listener.onProgress("VMRunner already patched");
            return;
        }
        
        String old = "const-string v0, \"pairipcore\"";
        String replacement = "const-string v0, \"_Pairip_CoreX\"\n" +
            "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n\n" +
            "    const-string v0, \"pairipcore\"";
        
        if (content.contains(old)) {
            content = content.replace(old, replacement);
            Files.writeString(vmrunner, content);
            listener.onSuccess("Patched VMRunner.smali");
        } else {
            listener.onError("Could not patch VMRunner.smali");
        }
    }
    
    private void applyNativePatches(Path soPath, java.util.List<NativePatch> patches) throws IOException {
        byte[] data = Files.readAllBytes(soPath);
        
        byte[] returnTrue = new byte[] {0x20, 0x00, (byte)0x80, (byte)0xD2, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
        
        for (NativePatch patch : patches) {
            listener.onProgress("Patching: " + patch.name);
            for (String offsetStr : patch.offsets) {
                int offset = Integer.parseInt(offsetStr.replace("0x", ""), 16);
                System.arraycopy(returnTrue, 0, data, offset, Math.min(returnTrue.length, data.length - offset));
            }
        }
        
        Files.write(soPath, data);
    }
    
    private void applyBsdiff(Path soPath, File patchFile) throws Exception {
        // Pure Java bsdiff - works on both CLI and Android
        byte[] oldBytes = Files.readAllBytes(soPath);
        byte[] patchBytes = Files.readAllBytes(patchFile.toPath());
        
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        io.sigpipe.jbsdiff.Patch.patch(oldBytes, patchBytes, out);
        Files.write(soPath, out.toByteArray());
        listener.onSuccess("Applied bsdiff patch (pairip bypass)");
    }
    
    private void signApk(Path input, Path output, String keystore) throws Exception {
        // Try apksigner first
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "apksigner", "sign",
                "--ks", keystore,
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                "--out", output.toString(),
                input.toString()
            );
            if (pb.start().waitFor() == 0) return;
        } catch (Exception ignored) {}
        
        // Fallback to jarsigner
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        ProcessBuilder pb = new ProcessBuilder(
            "jarsigner",
            "-keystore", keystore,
            "-storepass", "android",
            "-keypass", "android",
            output.toString(), "key"
        );
        pb.start().waitFor();
    }
    
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
    
    // Config classes
    public static class PatchConfig {
        public boolean corex = false;
        public String keystore;
        public File bsdiffPatch;  // Restores pairip-stripped code
        public java.util.List<NativePatch> patches;
    }
    
    public static class NativePatch {
        public String name;
        public java.util.List<String> offsets;
    }
}

package com.bitmanager.core;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class Patcher {
    
    public interface ProgressListener {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String message);
        default void onDebug(String message) {} // Optional debug logging
    }
    
    private final ProgressListener listener;
    private boolean debugMode = false;
    
    public Patcher(ProgressListener listener) {
        this.listener = listener != null ? listener : new ProgressListener() {
            public void onProgress(String msg) { System.out.println("[*] " + msg); }
            public void onSuccess(String msg) { System.out.println("[✓] " + msg); }
            public void onError(String msg) { System.err.println("[✗] " + msg); }
        };
    }
    
    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
    }
    
    private void debug(String msg) {
        if (debugMode) listener.onDebug("[DEBUG] " + msg);
    }
    
    public boolean patch(File inputApk, File outputApk, PatchConfig config) throws Exception {
        if (!config.corex) {
            return patchDirect(inputApk, outputApk, config);
        }
        return patchWithApktool(inputApk, outputApk, config);
    }
    
    private boolean patchDirect(File inputApk, File outputApk, PatchConfig config) throws Exception {
        listener.onProgress("Using direct patch mode...");
        debug("Input APK: " + inputApk.getAbsolutePath() + " (" + inputApk.length() + " bytes)");
        debug("Output APK: " + outputApk.getAbsolutePath());
        
        String libPath = "lib/arm64-v8a/libil2cpp.so";
        byte[] libData;
        
        // Read lib
        debug("Reading " + libPath + "...");
        try (ZipFile zip = new ZipFile(inputApk)) {
            debug("Input APK has " + zip.size() + " entries");
            ZipEntry entry = zip.getEntry(libPath);
            if (entry == null) throw new IOException(libPath + " not found");
            debug("lib entry: size=" + entry.getSize() + ", compressed=" + entry.getCompressedSize() + ", method=" + entry.getMethod());
            try (InputStream is = zip.getInputStream(entry)) {
                libData = is.readAllBytes();
            }
        }
        debug("Loaded lib: " + libData.length + " bytes");
        
        // Apply bsdiff
        if (config.bsdiffPatch != null) {
            listener.onProgress("Applying bsdiff patch...");
            debug("Bsdiff file: " + config.bsdiffPatch.getAbsolutePath() + " (" + config.bsdiffPatch.length() + " bytes)");
            byte[] patch = Files.readAllBytes(config.bsdiffPatch.toPath());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            io.sigpipe.jbsdiff.Patch.patch(libData, patch, out);
            int oldSize = libData.length;
            libData = out.toByteArray();
            debug("Bsdiff: " + oldSize + " -> " + libData.length + " bytes");
            listener.onSuccess("Bsdiff applied");
        }
        
        // Apply offset patches
        if (config.patches != null && !config.patches.isEmpty()) {
            listener.onProgress("Applying offset patches...");
            byte[] ret1 = {0x20, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
            int patchCount = 0;
            for (NativePatch p : config.patches) {
                debug("Patch: " + p.name + " (" + p.offsets.size() + " offsets)");
                for (String off : p.offsets) {
                    int o = Integer.parseInt(off.replace("0x", ""), 16);
                    if (o + ret1.length <= libData.length) {
                        System.arraycopy(ret1, 0, libData, o, ret1.length);
                        patchCount++;
                    } else {
                        debug("WARNING: offset " + off + " out of bounds (lib size: " + libData.length + ")");
                    }
                }
            }
            debug("Applied " + patchCount + " offset patches");
            listener.onSuccess("Offset patches applied");
        }
        
        // Write APK to temp file first, then move
        listener.onProgress("Writing APK...");
        File tempOut = new File(outputApk.getParentFile(), "temp_" + System.currentTimeMillis() + ".apk");
        debug("Writing to temp: " + tempOut.getAbsolutePath());
        writeApk(inputApk, tempOut, libPath, libData);
        debug("Temp APK written: " + tempOut.length() + " bytes");
        
        // Move to final location
        if (outputApk.exists()) {
            debug("Deleting existing output: " + outputApk.delete());
        }
        if (!tempOut.renameTo(outputApk)) {
            debug("Rename failed, copying instead");
            Files.copy(tempOut.toPath(), outputApk.toPath());
            tempOut.delete();
        }
        debug("Final APK: " + outputApk.length() + " bytes");
        
        // Verify ZIP is valid
        listener.onProgress("Verifying APK...");
        try (ZipFile verify = new ZipFile(outputApk)) {
            int stored = 0, deflated = 0;
            var entries = verify.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.getMethod() == ZipEntry.STORED) stored++;
                else deflated++;
            }
            debug("Entries: " + stored + " STORED, " + deflated + " DEFLATED");
            listener.onProgress("APK has " + verify.size() + " entries, size: " + outputApk.length());
        }
        
        // Sign
        listener.onProgress("Signing...");
        signApk(outputApk, config.keystore);
        listener.onSuccess("Signed");
        
        listener.onSuccess("Done: " + outputApk.getAbsolutePath());
        return true;
    }
    
    private void writeApk(File in, File out, String libPath, byte[] libData) throws IOException {
        try (ZipFile oldZip = new ZipFile(in);
             FileOutputStream fos = new FileOutputStream(out);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos, 65536))) {
            
            var entries = oldZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry old = entries.nextElement();
                ZipEntry neu = new ZipEntry(old.getName());
                String name = old.getName();
                
                if (name.equals(libPath)) {
                    // Patched lib - from memory, STORED
                    neu.setMethod(ZipEntry.STORED);
                    neu.setSize(libData.length);
                    neu.setCompressedSize(libData.length);
                    CRC32 crc = new CRC32();
                    crc.update(libData);
                    neu.setCrc(crc.getValue());
                    zos.putNextEntry(neu);
                    zos.write(libData);
                } else if (name.endsWith(".so") || name.equals("resources.arsc") || 
                           old.getMethod() == ZipEntry.STORED) {
                    // Must preserve STORED: .so files, resources.arsc, and originally STORED files
                    neu.setMethod(ZipEntry.STORED);
                    neu.setSize(old.getSize());
                    neu.setCompressedSize(old.getSize());
                    neu.setCrc(old.getCrc());
                    zos.putNextEntry(neu);
                    try (InputStream is = oldZip.getInputStream(old)) {
                        is.transferTo(zos);
                    }
                } else {
                    // Compressed files - recompress
                    neu.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(neu);
                    try (InputStream is = oldZip.getInputStream(old)) {
                        is.transferTo(zos);
                    }
                }
                zos.closeEntry();
            }
            
            zos.finish();
        }
        
        // Force sync to disk
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.getFD().sync();
        }
    }
    
    private void signApk(File apk, String ks) throws Exception {
        debug("Signing APK: " + apk.getAbsolutePath());
        debug("Keystore: " + ks);
        listener.onProgress("Signing file: " + apk.getAbsolutePath() + " (exists: " + apk.exists() + ", size: " + apk.length() + ")");
        
        // Try apksig (Android)
        try {
            Class.forName("com.android.apksig.ApkSigner");
            debug("Using apksig library");
            signWithApksig(apk, ks);
            return;
        } catch (ClassNotFoundException e) {
            debug("apksig not found: " + e.getMessage());
        }
        
        // Try apksigner (desktop)
        try {
            debug("Trying apksigner command");
            Process p = new ProcessBuilder("apksigner", "sign", "--ks", ks,
                "--ks-pass", "pass:android", "--key-pass", "pass:android",
                apk.getAbsolutePath()).start();
            if (p.waitFor() == 0) {
                debug("apksigner succeeded");
                return;
            }
            debug("apksigner failed with exit code: " + p.exitValue());
        } catch (Exception e) {
            debug("apksigner error: " + e.getMessage());
        }
        
        // jarsigner fallback
        debug("Falling back to jarsigner");
        new ProcessBuilder("jarsigner", "-keystore", ks, "-storepass", "android",
            "-keypass", "android", apk.getAbsolutePath(), "key").start().waitFor();
    }
    
    private void signWithApksig(File apk, String ks) throws Exception {
        debug("Loading keystore...");
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ks)) {
            keyStore.load(fis, "android".toCharArray());
        }
        
        String alias = keyStore.aliases().nextElement();
        debug("Using alias: " + alias);
        java.security.PrivateKey key = (java.security.PrivateKey) keyStore.getKey(alias, "android".toCharArray());
        java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) keyStore.getCertificate(alias);
        
        // apksig truncates output file before reading input - must use different files!
        File signedApk = new File(apk.getParent(), "signed_" + System.currentTimeMillis() + ".apk");
        debug("Signing to temp file: " + signedApk.getAbsolutePath());
        
        Class<?> scb = Class.forName("com.android.apksig.ApkSigner$SignerConfig$Builder");
        Object sc = scb.getConstructor(String.class, java.security.PrivateKey.class, java.util.List.class)
            .newInstance("signer", key, java.util.Collections.singletonList(cert));
        Object cfg = scb.getMethod("build").invoke(sc);
        
        Class<?> asb = Class.forName("com.android.apksig.ApkSigner$Builder");
        Object b = asb.getConstructor(java.util.List.class).newInstance(java.util.Collections.singletonList(cfg));
        asb.getMethod("setInputApk", File.class).invoke(b, apk);
        asb.getMethod("setOutputApk", File.class).invoke(b, signedApk);
        asb.getMethod("setV1SigningEnabled", boolean.class).invoke(b, true);
        asb.getMethod("setV2SigningEnabled", boolean.class).invoke(b, true);
        asb.getMethod("setV3SigningEnabled", boolean.class).invoke(b, false);
        
        debug("Calling apksig sign()...");
        Object signer = asb.getMethod("build").invoke(b);
        signer.getClass().getMethod("sign").invoke(signer);
        
        debug("Signed APK size: " + signedApk.length() + " bytes");
        listener.onProgress("Signed APK: " + signedApk.length() + " bytes");
        
        // Replace original with signed
        debug("Replacing original with signed APK");
        apk.delete();
        if (!signedApk.renameTo(apk)) {
            debug("Rename failed, copying instead");
            java.nio.file.Files.copy(signedApk.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            signedApk.delete();
        }
        
        // Verify final APK
        try (ZipFile verify = new ZipFile(apk)) {
            listener.onProgress("Final APK: " + apk.length() + " bytes, " + verify.size() + " entries");
        }
    }
    
    private boolean patchWithApktool(File inputApk, File outputApk, PatchConfig config) throws Exception {
        listener.onError("CoreX mode requires apktool - use bsdiff instead");
        return false;
    }
    
    public static class PatchConfig {
        public boolean corex = false;
        public String keystore;
        public File bsdiffPatch;
        public java.util.List<NativePatch> patches;
    }
    
    public static class NativePatch {
        public String name;
        public java.util.List<String> offsets;
    }
}

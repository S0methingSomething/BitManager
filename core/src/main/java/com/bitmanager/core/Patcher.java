package com.bitmanager.core;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

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
        if (!config.corex) {
            return patchDirect(inputApk, outputApk, config);
        }
        return patchWithApktool(inputApk, outputApk, config);
    }
    
    private boolean patchDirect(File inputApk, File outputApk, PatchConfig config) throws Exception {
        listener.onProgress("Using direct patch mode...");
        
        String libPath = "lib/arm64-v8a/libil2cpp.so";
        byte[] libData;
        
        // Read lib
        try (ZipFile zip = new ZipFile(inputApk)) {
            ZipEntry entry = zip.getEntry(libPath);
            if (entry == null) throw new IOException(libPath + " not found");
            try (InputStream is = zip.getInputStream(entry)) {
                libData = is.readAllBytes();
            }
        }
        
        // Apply bsdiff
        if (config.bsdiffPatch != null) {
            listener.onProgress("Applying bsdiff patch...");
            byte[] patch = Files.readAllBytes(config.bsdiffPatch.toPath());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            io.sigpipe.jbsdiff.Patch.patch(libData, patch, out);
            libData = out.toByteArray();
            listener.onSuccess("Bsdiff applied");
        }
        
        // Apply offset patches
        if (config.patches != null && !config.patches.isEmpty()) {
            listener.onProgress("Applying offset patches...");
            byte[] ret1 = {0x20, 0x00, (byte)0x80, (byte)0xD2, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
            for (NativePatch p : config.patches) {
                for (String off : p.offsets) {
                    int o = Integer.parseInt(off.replace("0x", ""), 16);
                    if (o + ret1.length <= libData.length)
                        System.arraycopy(ret1, 0, libData, o, ret1.length);
                }
            }
            listener.onSuccess("Offset patches applied");
        }
        
        // Write APK to temp file first, then move
        listener.onProgress("Writing APK...");
        File tempOut = new File(outputApk.getParentFile(), "temp_" + System.currentTimeMillis() + ".apk");
        writeApk(inputApk, tempOut, libPath, libData);
        
        // Move to final location
        if (outputApk.exists()) outputApk.delete();
        if (!tempOut.renameTo(outputApk)) {
            // If rename fails, copy
            Files.copy(tempOut.toPath(), outputApk.toPath());
            tempOut.delete();
        }
        
        // Verify ZIP is valid
        listener.onProgress("Verifying APK...");
        try (ZipFile verify = new ZipFile(outputApk)) {
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
                
                if (old.getName().equals(libPath)) {
                    // Patched lib - from memory
                    neu.setMethod(ZipEntry.STORED);
                    neu.setSize(libData.length);
                    neu.setCompressedSize(libData.length);
                    CRC32 crc = new CRC32();
                    crc.update(libData);
                    neu.setCrc(crc.getValue());
                    zos.putNextEntry(neu);
                    zos.write(libData);
                } else if (old.getName().endsWith(".so")) {
                    // Other .so - must be STORED, stream copy
                    neu.setMethod(ZipEntry.STORED);
                    neu.setSize(old.getSize());
                    neu.setCompressedSize(old.getSize());
                    neu.setCrc(old.getCrc());
                    zos.putNextEntry(neu);
                    try (InputStream is = oldZip.getInputStream(old)) {
                        is.transferTo(zos);
                    }
                } else {
                    // Other files - preserve compression, stream copy
                    neu.setMethod(old.getMethod());
                    if (old.getMethod() == ZipEntry.STORED) {
                        neu.setSize(old.getSize());
                        neu.setCompressedSize(old.getSize());
                        neu.setCrc(old.getCrc());
                    }
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
        listener.onProgress("Signing file: " + apk.getAbsolutePath() + " (exists: " + apk.exists() + ", size: " + apk.length() + ")");
        
        // Try apksig (Android)
        try {
            Class.forName("com.android.apksig.ApkSigner");
            signWithApksig(apk, ks);
            return;
        } catch (ClassNotFoundException ignored) {}
        
        // Try apksigner (desktop)
        try {
            Process p = new ProcessBuilder("apksigner", "sign", "--ks", ks,
                "--ks-pass", "pass:android", "--key-pass", "pass:android",
                apk.getAbsolutePath()).start();
            if (p.waitFor() == 0) return;
        } catch (Exception ignored) {}
        
        // jarsigner fallback
        new ProcessBuilder("jarsigner", "-keystore", ks, "-storepass", "android",
            "-keypass", "android", apk.getAbsolutePath(), "key").start().waitFor();
    }
    
    private void signWithApksig(File apk, String ks) throws Exception {
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ks)) {
            keyStore.load(fis, "android".toCharArray());
        }
        
        String alias = keyStore.aliases().nextElement();
        java.security.PrivateKey key = (java.security.PrivateKey) keyStore.getKey(alias, "android".toCharArray());
        java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) keyStore.getCertificate(alias);
        
        Class<?> scb = Class.forName("com.android.apksig.ApkSigner$SignerConfig$Builder");
        Object sc = scb.getConstructor(String.class, java.security.PrivateKey.class, java.util.List.class)
            .newInstance("signer", key, java.util.Collections.singletonList(cert));
        Object cfg = scb.getMethod("build").invoke(sc);
        
        Class<?> asb = Class.forName("com.android.apksig.ApkSigner$Builder");
        Object b = asb.getConstructor(java.util.List.class).newInstance(java.util.Collections.singletonList(cfg));
        asb.getMethod("setInputApk", File.class).invoke(b, apk);
        asb.getMethod("setOutputApk", File.class).invoke(b, apk);
        asb.getMethod("setV1SigningEnabled", boolean.class).invoke(b, true);
        asb.getMethod("setV2SigningEnabled", boolean.class).invoke(b, true);
        asb.getMethod("setV3SigningEnabled", boolean.class).invoke(b, false);
        
        Object signer = asb.getMethod("build").invoke(b);
        signer.getClass().getMethod("sign").invoke(signer);
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

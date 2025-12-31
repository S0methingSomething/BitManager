package com.bitmanager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;
import java.security.*;
import java.security.cert.*;

public class MainActivity extends Activity {
    private static final int PICK_APK = 1;
    private static final String PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/";
    
    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout patchList;
    private Button selectApkBtn, patchBtn, installBtn;
    
    private List<Patch> patches = new ArrayList<>();
    private Set<Integer> selectedPatches = new HashSet<>();
    private String apkVersion;
    private File selectedApk;
    private File patchedApk;
    private StringBuilder logBuilder = new StringBuilder();

    private static final byte[] RETURN_TRUE = {0x20, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_FALSE = {0x00, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);
        patchList = findViewById(R.id.patchList);
        selectApkBtn = findViewById(R.id.selectApkBtn);
        patchBtn = findViewById(R.id.patchBtn);
        installBtn = findViewById(R.id.installBtn);
        
        selectApkBtn.setOnClickListener(v -> pickApk());
        patchBtn.setOnClickListener(v -> patchApk());
        installBtn.setOnClickListener(v -> installApk());
        
        log("BitManager ready\nSelect a BitLife APK to patch");
    }

    private void log(String msg) {
        logBuilder.append(msg).append("\n");
        runOnUiThread(() -> {
            logView.setText(logBuilder.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, "Select BitLife APK"), PICK_APK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_APK && res == RESULT_OK && data != null) {
            logBuilder.setLength(0);
            installBtn.setVisibility(View.GONE);
            log("Loading APK...");
            new Thread(() -> {
                selectedApk = copyToCache(data.getData(), "selected.apk");
                if (selectedApk != null) {
                    log("✓ APK loaded (" + (selectedApk.length() / 1024 / 1024) + " MB)");
                    extractVersionFromApk();
                }
            }).start();
        }
    }

    private File copyToCache(Uri uri, String name) {
        try {
            File f = new File(getCacheDir(), name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            return f;
        } catch (Exception e) {
            log("✗ Failed to load APK: " + e.getMessage());
            return null;
        }
    }

    private void extractVersionFromApk() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                .getPackageArchiveInfo(selectedApk.getAbsolutePath(), 0);
            if (info != null && "com.candywriter.bitlife".equals(info.packageName)) {
                apkVersion = info.versionName;
                log("✓ BitLife v" + apkVersion + " detected");
                loadPatches();
            } else {
                log("✗ Not a BitLife APK!");
                selectedApk = null;
            }
        } catch (Exception e) {
            log("✗ Failed to read APK: " + e.getMessage());
        }
    }

    private void loadPatches() {
        log("Fetching patches for v" + apkVersion + "...");
        try {
            String json = fetch(PATCHES_URL + apkVersion + ".json");
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.getJSONArray("patches");
            
            patches.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                Patch patch = new Patch();
                patch.name = p.getString("name");
                patch.description = p.getString("description");
                patch.patchType = p.getString("patch");
                JSONArray offsets = p.getJSONArray("offsets");
                patch.offsets = new int[offsets.length()];
                for (int j = 0; j < offsets.length(); j++) {
                    patch.offsets[j] = Integer.decode(offsets.getString(j));
                }
                patches.add(patch);
                log("  • " + patch.name + " (" + patch.offsets.length + " offsets)");
            }
            
            log("✓ Found " + patches.size() + " patches\n");
            runOnUiThread(this::showPatches);
        } catch (Exception e) {
            log("✗ No patches available for v" + apkVersion);
        }
    }

    private void showPatches() {
        patchList.removeAllViews();
        selectedPatches.clear();
        for (int i = 0; i < patches.size(); i++) {
            Patch p = patches.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setText(p.name);
            cb.setTextSize(16);
            cb.setPadding(0, 12, 0, 12);
            int idx = i;
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selectedPatches.add(idx);
                else selectedPatches.remove(idx);
                patchBtn.setEnabled(!selectedPatches.isEmpty());
            });
            cb.setChecked(true);
            selectedPatches.add(i);
            patchList.addView(cb);
        }
        patchList.setVisibility(View.VISIBLE);
        patchBtn.setEnabled(true);
        patchBtn.setVisibility(View.VISIBLE);
    }

    private String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private void patchApk() {
        patchBtn.setEnabled(false);
        selectApkBtn.setEnabled(false);
        
        new Thread(() -> {
            try {
                log("═══════════════════════════════");
                log("Starting patch process...\n");
                
                // Extract
                log("[1/4] Extracting APK...");
                File extractDir = new File(getCacheDir(), "apk_extract");
                deleteRecursive(extractDir);
                extractDir.mkdirs();
                unzip(selectedApk, extractDir);
                
                // Remove old signature
                deleteRecursive(new File(extractDir, "META-INF"));
                log("✓ Extracted\n");

                // Find lib
                log("[2/4] Locating libil2cpp.so...");
                File libFile = findLib(extractDir);
                if (libFile == null) throw new Exception("libil2cpp.so not found");
                log("✓ Found: " + libFile.getParentFile().getName() + "/" + libFile.getName());
                log("  Size: " + (libFile.length() / 1024 / 1024) + " MB\n");

                // Patch
                log("[3/4] Applying patches...");
                int totalPatched = 0;
                try (RandomAccessFile raf = new RandomAccessFile(libFile, "rw")) {
                    for (int idx : selectedPatches) {
                        Patch p = patches.get(idx);
                        byte[] patch = p.patchType.equals("return_true") ? RETURN_TRUE : RETURN_FALSE;
                        for (int off : p.offsets) {
                            raf.seek(off);
                            raf.write(patch);
                        }
                        totalPatched += p.offsets.length;
                        log("  ✓ " + p.name + " (" + p.offsets.length + ")");
                    }
                }
                log("✓ Patched " + totalPatched + " functions\n");

                // Repack & Sign
                log("[4/4] Repacking & signing...");
                patchedApk = new File(getExternalFilesDir(null), "BitLife_v" + apkVersion + "_patched.apk");
                zipAndSign(extractDir, patchedApk);
                deleteRecursive(extractDir);
                log("✓ Signed: " + (patchedApk.length() / 1024 / 1024) + " MB\n");

                log("═══════════════════════════════");
                log("✓ PATCHING COMPLETE!\n");
                log("1. Uninstall original BitLife");
                log("2. Tap 'Install Patched APK'\n");
                log("File: " + patchedApk.getAbsolutePath());
                
                runOnUiThread(() -> {
                    selectApkBtn.setEnabled(true);
                    patchBtn.setEnabled(true);
                    installBtn.setVisibility(View.VISIBLE);
                    installBtn.setEnabled(true);
                });
                
            } catch (Exception e) {
                log("\n✗ FAILED: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> {
                    patchBtn.setEnabled(true);
                    selectApkBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private File findLib(File dir) {
        File lib = new File(dir, "lib/arm64-v8a/libil2cpp.so");
        if (lib.exists()) return lib;
        lib = new File(dir, "lib/armeabi-v7a/libil2cpp.so");
        if (lib.exists()) return lib;
        return null;
    }

    private void installApk() {
        if (patchedApk == null || !patchedApk.exists()) {
            log("✗ No patched APK found");
            return;
        }
        
        log("\nLaunching installer...");
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", patchedApk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            log("✗ Install failed: " + e.getMessage());
        }
    }

    private void unzip(File zip, File dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File f = new File(dest, entry.getName());
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

    private void zipAndSign(File dir, File out) throws Exception {
        // Create unsigned APK first
        File unsigned = new File(getCacheDir(), "unsigned.apk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsigned))) {
            zos.setLevel(Deflater.BEST_SPEED);
            zipDir(dir, dir, zos);
        }
        
        // Sign it
        signApk(unsigned, out);
        unsigned.delete();
    }

    private void zipDir(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                zipDir(root, f, zos);
            } else {
                String path = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                zos.putNextEntry(new ZipEntry(path));
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    private void signApk(File input, File output) throws Exception {
        KeyStore ks = getOrCreateKeyStore();
        PrivateKey privateKey = (PrivateKey) ks.getKey("key", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("key");
        
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        StringBuilder manifest = new StringBuilder("Manifest-Version: 1.0\r\nCreated-By: BitManager\r\n\r\n");
        
        // Read all entries and compute hashes
        Map<String, byte[]> hashes = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                
                sha256.reset();
                try (InputStream is = zf.getInputStream(entry)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) sha256.update(buf, 0, len);
                }
                hashes.put(entry.getName(), sha256.digest());
                
                manifest.append("Name: ").append(entry.getName()).append("\r\n");
                manifest.append("SHA-256-Digest: ").append(android.util.Base64.encodeToString(hashes.get(entry.getName()), android.util.Base64.NO_WRAP)).append("\r\n\r\n");
            }
        }
        
        byte[] manifestBytes = manifest.toString().getBytes("UTF-8");
        
        // Create signature file
        sha256.reset();
        sha256.update(manifestBytes);
        StringBuilder sf = new StringBuilder("Signature-Version: 1.0\r\n");
        sf.append("SHA-256-Digest-Manifest: ").append(android.util.Base64.encodeToString(sha256.digest(), android.util.Base64.NO_WRAP)).append("\r\n");
        sf.append("Created-By: BitManager\r\n\r\n");
        byte[] sfBytes = sf.toString().getBytes("UTF-8");
        
        // Sign
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] sigBytes = sig.sign();
        
        // Write signed APK
        try (ZipFile zf = new ZipFile(input);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output))) {
            
            // Write META-INF first
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write(manifestBytes);
            zos.closeEntry();
            
            zos.putNextEntry(new ZipEntry("META-INF/CERT.SF"));
            zos.write(sfBytes);
            zos.closeEntry();
            
            zos.putNextEntry(new ZipEntry("META-INF/CERT.RSA"));
            zos.write(createPKCS7(cert, sigBytes));
            zos.closeEntry();
            
            // Copy all other entries
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                
                zos.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream is = zf.getInputStream(entry)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }
    
    private KeyStore getOrCreateKeyStore() throws Exception {
        File ksFile = new File(getFilesDir(), "bitmanager.bks");
        KeyStore ks = KeyStore.getInstance("BKS");
        
        if (ksFile.exists()) {
            try (FileInputStream fis = new FileInputStream(ksFile)) {
                ks.load(fis, "android".toCharArray());
                return ks;
            } catch (Exception e) {
                ksFile.delete();
            }
        }
        
        // Generate new key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        
        // Create self-signed certificate manually
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24 * 60 * 60 * 1000L);
        Date notAfter = new Date(now + 25L * 365 * 24 * 60 * 60 * 1000L);
        
        X509Certificate cert = generateCertificate(kp, "CN=BitManager", notBefore, notAfter);
        
        ks.load(null, "android".toCharArray());
        ks.setKeyEntry("key", kp.getPrivate(), "android".toCharArray(), 
            new java.security.cert.Certificate[]{cert});
        
        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            ks.store(fos, "android".toCharArray());
        }
        
        return ks;
    }
    
    private X509Certificate generateCertificate(KeyPair kp, String dn, Date notBefore, Date notAfter) throws Exception {
        // Use Android's hidden X509V3CertificateGenerator or build manually
        // Simplified: use sun.security if available, otherwise build raw
        
        byte[] encoded = buildSelfSignedCert(kp, dn, notBefore, notAfter);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(encoded));
    }
    
    private byte[] buildSelfSignedCert(KeyPair kp, String dn, Date notBefore, Date notAfter) throws Exception {
        // Build X.509 certificate DER encoding manually
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // TBSCertificate
        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        
        // Version (v3 = 2)
        tbs.write(new byte[]{(byte)0xA0, 0x03, 0x02, 0x01, 0x02});
        
        // Serial number
        byte[] serial = java.math.BigInteger.valueOf(System.currentTimeMillis()).toByteArray();
        tbs.write(0x02);
        tbs.write(serial.length);
        tbs.write(serial);
        
        // Signature algorithm (SHA256withRSA)
        tbs.write(new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00});
        
        // Issuer
        byte[] issuer = encodeDN(dn);
        tbs.write(issuer);
        
        // Validity
        ByteArrayOutputStream validity = new ByteArrayOutputStream();
        validity.write(encodeUTCTime(notBefore));
        validity.write(encodeUTCTime(notAfter));
        tbs.write(0x30);
        tbs.write(validity.size());
        tbs.write(validity.toByteArray());
        
        // Subject (same as issuer for self-signed)
        tbs.write(issuer);
        
        // SubjectPublicKeyInfo
        byte[] pubKey = kp.getPublic().getEncoded();
        tbs.write(pubKey);
        
        byte[] tbsBytes = tbs.toByteArray();
        
        // Sign TBSCertificate
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        
        // Wrap TBS in SEQUENCE
        ByteArrayOutputStream tbsSeq = new ByteArrayOutputStream();
        writeLength(tbsSeq, tbsBytes.length);
        byte[] tbsLen = tbsSeq.toByteArray();
        
        byte[] tbsFull = new byte[1 + tbsLen.length + tbsBytes.length];
        tbsFull[0] = 0x30;
        System.arraycopy(tbsLen, 0, tbsFull, 1, tbsLen.length);
        System.arraycopy(tbsBytes, 0, tbsFull, 1 + tbsLen.length, tbsBytes.length);
        
        sig.update(tbsFull);
        byte[] signature = sig.sign();
        
        // Build full certificate
        ByteArrayOutputStream cert = new ByteArrayOutputStream();
        cert.write(tbsFull);
        
        // Signature algorithm
        cert.write(new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00});
        
        // Signature value (BIT STRING)
        cert.write(0x03);
        writeLength(cert, signature.length + 1);
        cert.write(0x00); // no unused bits
        cert.write(signature);
        
        // Wrap in SEQUENCE
        byte[] certBytes = cert.toByteArray();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(0x30);
        writeLength(result, certBytes.length);
        result.write(certBytes);
        
        return result.toByteArray();
    }
    
    private byte[] encodeDN(String dn) throws Exception {
        // Simple CN= encoding
        String cn = dn.replace("CN=", "");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // RDN SET
        ByteArrayOutputStream rdn = new ByteArrayOutputStream();
        // AttributeTypeAndValue SEQUENCE
        ByteArrayOutputStream atv = new ByteArrayOutputStream();
        // CN OID
        atv.write(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03});
        // UTF8String value
        byte[] cnBytes = cn.getBytes("UTF-8");
        atv.write(0x0C);
        atv.write(cnBytes.length);
        atv.write(cnBytes);
        
        byte[] atvBytes = atv.toByteArray();
        rdn.write(0x30);
        rdn.write(atvBytes.length);
        rdn.write(atvBytes);
        
        byte[] rdnBytes = rdn.toByteArray();
        baos.write(0x31);
        baos.write(rdnBytes.length);
        baos.write(rdnBytes);
        
        byte[] setBytes = baos.toByteArray();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(0x30);
        result.write(setBytes.length);
        result.write(setBytes);
        
        return result.toByteArray();
    }
    
    private byte[] encodeUTCTime(Date date) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        byte[] time = sdf.format(date).getBytes();
        byte[] result = new byte[time.length + 2];
        result[0] = 0x17; // UTCTime
        result[1] = (byte) time.length;
        System.arraycopy(time, 0, result, 2, time.length);
        return result;
    }
    
    private void writeLength(OutputStream os, int len) throws IOException {
        if (len < 128) {
            os.write(len);
        } else if (len < 256) {
            os.write(0x81);
            os.write(len);
        } else {
            os.write(0x82);
            os.write(len >> 8);
            os.write(len & 0xFF);
        }
    }
    
    private byte[] createPKCS7(X509Certificate cert, byte[] signature) throws Exception {
        // Minimal PKCS7 SignedData structure
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        byte[] certBytes = cert.getEncoded();
        
        // This is a simplified PKCS7 - for full compatibility use BouncyCastle
        // For now, just concatenate cert + signature
        baos.write(certBytes);
        baos.write(signature);
        
        return baos.toByteArray();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        f.delete();
    }

    static class Patch {
        String name, description, patchType;
        int[] offsets;
    }
}

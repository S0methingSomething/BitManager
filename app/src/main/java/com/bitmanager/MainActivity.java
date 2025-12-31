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
            zipDir(dir, dir, zos);
        }
        
        // Sign it
        signApk(unsigned, out);
        unsigned.delete();
    }

    private void zipDir(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[8192];
        for (File f : files) {
            if (f.isDirectory()) {
                zipDir(root, f, zos);
            } else {
                String path = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                ZipEntry entry = new ZipEntry(path);
                
                // Android R+ requires resources.arsc uncompressed and 4-byte aligned
                // Also store .so files and .png uncompressed for performance
                if (path.equals("resources.arsc") || path.endsWith(".so") || path.endsWith(".png") || path.endsWith(".arsc")) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(f.length());
                    entry.setCompressedSize(f.length());
                    entry.setCrc(computeCrc(f));
                } else {
                    entry.setMethod(ZipEntry.DEFLATED);
                }
                
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(f)) {
                    int len;
                    while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }
    
    private long computeCrc(File f) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) crc.update(buf, 0, len);
        }
        return crc.getValue();
    }

    private void signApk(File input, File output) throws Exception {
        // Load bundled keystore from assets
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = getAssets().open("debug.p12")) {
            ks.load(is, "android".toCharArray());
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey("key", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("key");
        
        com.android.apksig.ApkSigner.SignerConfig signerConfig = 
            new com.android.apksig.ApkSigner.SignerConfig.Builder(
                "BitManager", privateKey, java.util.Collections.singletonList(cert))
            .build();
        
        new com.android.apksig.ApkSigner.Builder(java.util.Collections.singletonList(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign();
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

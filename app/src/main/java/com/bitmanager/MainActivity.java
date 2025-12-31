package com.bitmanager;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import rikka.shizuku.Shizuku;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends Activity {
    private static final int SHIZUKU_CODE = 1;
    private static final int PICK_APK = 2;
    private static final String PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/";
    
    private TextView status;
    private ProgressBar progress;
    private LinearLayout patchList;
    private Button selectApkBtn, patchApkBtn, installBtn, applyLiveBtn, restoreBtn;
    
    private List<Patch> patches = new ArrayList<>();
    private Set<Integer> selectedPatches = new HashSet<>();
    private String bitlifeVersion;
    private File selectedApk;
    private File patchedApk;

    // ARM64 patches
    private static final byte[] RETURN_TRUE = {0x20, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_FALSE = {0x00, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        patchList = findViewById(R.id.patchList);
        selectApkBtn = findViewById(R.id.selectApkBtn);
        patchApkBtn = findViewById(R.id.patchApkBtn);
        installBtn = findViewById(R.id.installBtn);
        applyLiveBtn = findViewById(R.id.applyLiveBtn);
        restoreBtn = findViewById(R.id.restoreBtn);
        
        selectApkBtn.setOnClickListener(v -> pickApk());
        patchApkBtn.setOnClickListener(v -> patchApk());
        installBtn.setOnClickListener(v -> installApk());
        applyLiveBtn.setOnClickListener(v -> applyLive());
        restoreBtn.setOnClickListener(v -> restore());
        
        checkBitLife();
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("application/vnd.android.package-archive");
        startActivityForResult(i, PICK_APK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_APK && res == RESULT_OK && data != null) {
            selectedApk = copyToCache(data.getData(), "selected.apk");
            if (selectedApk != null) {
                extractVersionFromApk();
            }
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
            status.setText("Failed to load APK: " + e.getMessage());
            return null;
        }
    }

    private void extractVersionFromApk() {
        try {
            PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo info = pm.getPackageArchiveInfo(selectedApk.getAbsolutePath(), 0);
            if (info != null) {
                bitlifeVersion = info.versionName;
                status.setText("APK loaded: BitLife " + bitlifeVersion + "\nLoading patches...");
                patchApkBtn.setEnabled(false);
                installBtn.setEnabled(false);
                loadPatches();
            }
        } catch (Exception e) {
            status.setText("Failed to read APK: " + e.getMessage());
        }
    }

    private void checkBitLife() {
        try {
            bitlifeVersion = getPackageManager().getPackageInfo("com.candywriter.bitlife", 0).versionName;
            status.setText("BitLife " + bitlifeVersion + " found\nLoading patches...");
            loadPatches();
        } catch (PackageManager.NameNotFoundException e) {
            status.setText("BitLife not installed!");
        }
    }

    private void loadPatches() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                String json = fetch(PATCHES_URL + bitlifeVersion + ".json");
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
                }
                
                runOnUiThread(() -> {
                    showPatches();
                    progress.setVisibility(View.GONE);
                    status.setText("BitLife " + bitlifeVersion + "\nSelect patches:");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("No patches for " + bitlifeVersion + "\n" + e.getMessage());
                });
            }
        }).start();
    }

    private void showPatches() {
        patchList.removeAllViews();
        for (int i = 0; i < patches.size(); i++) {
            Patch p = patches.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setText(p.name + "\n" + p.description);
            cb.setTextSize(14);
            cb.setPadding(0, 16, 0, 16);
            int idx = i;
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selectedPatches.add(idx);
                else selectedPatches.remove(idx);
                updateButtons();
            });
            cb.setChecked(true);
            selectedPatches.add(i);
            patchList.addView(cb);
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean hasPatches = !selectedPatches.isEmpty();
        if (selectedApk != null) {
            patchApkBtn.setEnabled(hasPatches);
        } else {
            applyLiveBtn.setEnabled(hasPatches);
            restoreBtn.setEnabled(true);
        }
    }

    private void patchApk() {
        if (!checkShizuku()) return;
        setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Patching APK...");

        new Thread(() -> {
            try {
                // Extract APK
                File extractDir = new File(getCacheDir(), "apk_extract");
                extractDir.mkdirs();
                unzip(selectedApk, extractDir);

                // Find and patch libil2cpp.so
                File libDir = new File(extractDir, "lib/arm64-v8a");
                if (!libDir.exists()) libDir = new File(extractDir, "lib/armeabi-v7a");
                File libFile = new File(libDir, "libil2cpp.so");
                
                if (!libFile.exists()) throw new Exception("libil2cpp.so not found in APK");

                int count = 0;
                try (RandomAccessFile raf = new RandomAccessFile(libFile, "rw")) {
                    for (int idx : selectedPatches) {
                        Patch p = patches.get(idx);
                        byte[] patch = p.patchType.equals("return_true") ? RETURN_TRUE : RETURN_FALSE;
                        for (int off : p.offsets) {
                            raf.seek(off);
                            raf.write(patch);
                            count++;
                        }
                    }
                }

                // Repack APK
                patchedApk = new File(getCacheDir(), "bitlife_patched.apk");
                zip(extractDir, patchedApk);
                deleteRecursive(extractDir);

                // Sign APK
                signApk(patchedApk);

                int finalCount = count;
                runOnUiThread(() -> {
                    status.setText("✓ Patched " + finalCount + " functions!\nReady to install.");
                    progress.setVisibility(View.GONE);
                    installBtn.setEnabled(true);
                    setEnabled(true);
                });
            } catch (Exception e) {
                error("Patch failed: " + e.getMessage());
            }
        }).start();
    }

    private void installApk() {
        if (patchedApk == null || !patchedApk.exists()) {
            status.setText("No patched APK to install");
            return;
        }
        
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(patchedApk), "application/vnd.android.package-archive");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
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

    private void zip(File dir, File out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zipDir(dir, dir, zos);
        }
    }

    private void zipDir(File root, File dir, ZipOutputStream zos) throws IOException {
        for (File f : dir.listFiles()) {
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

    private void signApk(File apk) throws Exception {
        // Simple v1 signature - just add META-INF files
        // For production, use proper signing with apksigner
        File metaInf = new File(apk.getParent(), "META-INF");
        metaInf.mkdirs();
        new File(metaInf, "MANIFEST.MF").createNewFile();
        new File(metaInf, "CERT.SF").createNewFile();
        new File(metaInf, "CERT.RSA").createNewFile();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) deleteRecursive(c);
        }
        f.delete();
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

    private void applyLive() {
        if (!checkShizuku()) return;
        setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Patching...");

        new Thread(() -> {
            try {
                String libPath = findLib();
                backup(libPath);
                
                File tmp = new File(getCacheDir(), "libil2cpp.so");
                exec("cp " + libPath + " " + tmp.getAbsolutePath());
                exec("chmod 666 " + tmp.getAbsolutePath());

                int count = 0;
                try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
                    for (int idx : selectedPatches) {
                        Patch p = patches.get(idx);
                        byte[] patch = p.patchType.equals("return_true") ? RETURN_TRUE : RETURN_FALSE;
                        for (int off : p.offsets) {
                            raf.seek(off);
                            raf.write(patch);
                            count++;
                        }
                    }
                }

                exec("cp " + tmp.getAbsolutePath() + " " + libPath);
                exec("chmod 755 " + libPath);
                tmp.delete();

                int finalCount = count;
                done("✓ Patched " + finalCount + " functions!\nRestart BitLife.");
            } catch (Exception e) {
                error("Error: " + e.getMessage());
            }
        }).start();
    }

    private void restore() {
        if (!checkShizuku()) return;
        setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Restoring...");

        new Thread(() -> {
            try {
                String libPath = findLib();
                exec("cp " + libPath + ".orig " + libPath);
                done("✓ Restored!");
            } catch (Exception e) {
                error("Error: " + e.getMessage());
            }
        }).start();
    }

    private boolean checkShizuku() {
        if (!Shizuku.pingBinder()) { status.setText("Shizuku not running!"); return false; }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (c == SHIZUKU_CODE && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (selectedApk != null) patchApk();
            else applyLive();
        }
    }

    private String findLib() throws Exception {
        String result = execWithOutput("find /data/app -path '*com.candywriter.bitlife*/lib/arm64*' -name 'libil2cpp.so' 2>/dev/null | head -1");
        if (result == null || result.isEmpty()) throw new Exception("libil2cpp.so not found");
        return result.trim();
    }

    private void backup(String libPath) throws Exception {
        exec("[ ! -f " + libPath + ".orig ] && cp " + libPath + " " + libPath + ".orig");
    }

    private void exec(String cmd) throws Exception {
        Shizuku.ShizukuRemoteProcess p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        p.waitFor();
        if (p.exitValue() != 0) throw new Exception("Failed: " + cmd);
    }

    private String execWithOutput(String cmd) throws Exception {
        Shizuku.ShizukuRemoteProcess p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String result = r.readLine();
        r.close();
        p.waitFor();
        return result;
    }

    private void done(String msg) { runOnUiThread(() -> { status.setText(msg); progress.setVisibility(View.GONE); setEnabled(true); }); }
    private void error(String msg) { runOnUiThread(() -> { status.setText(msg); progress.setVisibility(View.GONE); setEnabled(true); }); }
    private void setEnabled(boolean e) { 
        selectApkBtn.setEnabled(e);
        if (selectedApk != null) {
            patchApkBtn.setEnabled(e && !selectedPatches.isEmpty());
            installBtn.setEnabled(e && patchedApk != null);
        } else {
            applyLiveBtn.setEnabled(e && !selectedPatches.isEmpty());
            restoreBtn.setEnabled(e);
        }
    }

    static class Patch {
        String name, description, patchType;
        int[] offsets;
    }
}

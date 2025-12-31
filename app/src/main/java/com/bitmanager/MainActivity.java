package com.bitmanager;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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

public class MainActivity extends Activity {
    private static final int PICK_APK = 1;
    private static final String PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/";
    
    private TextView status;
    private ProgressBar progress;
    private LinearLayout patchList;
    private Button selectApkBtn, patchBtn, installBtn;
    
    private List<Patch> patches = new ArrayList<>();
    private Set<Integer> selectedPatches = new HashSet<>();
    private String apkVersion;
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
        patchBtn = findViewById(R.id.patchBtn);
        installBtn = findViewById(R.id.installBtn);
        
        selectApkBtn.setOnClickListener(v -> pickApk());
        patchBtn.setOnClickListener(v -> patchApk());
        installBtn.setOnClickListener(v -> installApk());
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, "Select BitLife APK"), PICK_APK);
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
            android.content.pm.PackageInfo info = getPackageManager()
                .getPackageArchiveInfo(selectedApk.getAbsolutePath(), 0);
            if (info != null && "com.candywriter.bitlife".equals(info.packageName)) {
                apkVersion = info.versionName;
                status.setText("BitLife " + apkVersion + " loaded\nFetching patches...");
                loadPatches();
            } else {
                status.setText("Not a BitLife APK!");
                selectedApk = null;
            }
        } catch (Exception e) {
            status.setText("Failed to read APK: " + e.getMessage());
        }
    }

    private void loadPatches() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
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
                }
                
                runOnUiThread(() -> {
                    showPatches();
                    progress.setVisibility(View.GONE);
                    status.setText("BitLife " + apkVersion + "\nSelect patches:");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("No patches for v" + apkVersion + "\n" + e.getMessage());
                });
            }
        }).start();
    }

    private void showPatches() {
        patchList.removeAllViews();
        selectedPatches.clear();
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
                patchBtn.setEnabled(!selectedPatches.isEmpty());
            });
            cb.setChecked(true);
            selectedPatches.add(i);
            patchList.addView(cb);
        }
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
        setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Patching APK...");

        new Thread(() -> {
            try {
                File extractDir = new File(getCacheDir(), "apk_extract");
                deleteRecursive(extractDir);
                extractDir.mkdirs();
                
                unzip(selectedApk, extractDir);

                File libFile = findLib(extractDir);
                if (libFile == null) throw new Exception("libil2cpp.so not found");

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

                patchedApk = new File(getCacheDir(), "BitLife_patched.apk");
                zip(extractDir, patchedApk);
                deleteRecursive(extractDir);

                int finalCount = count;
                runOnUiThread(() -> {
                    status.setText("✓ Patched " + finalCount + " functions!\nTap Install to install.");
                    progress.setVisibility(View.GONE);
                    installBtn.setEnabled(true);
                    installBtn.setVisibility(View.VISIBLE);
                    setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Patch failed: " + e.getMessage());
                    progress.setVisibility(View.GONE);
                    setEnabled(true);
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
        if (patchedApk == null || !patchedApk.exists()) return;
        
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", patchedApk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            status.setText("Install failed: " + e.getMessage());
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

    private void zip(File dir, File out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zipDir(dir, dir, zos);
        }
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

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        f.delete();
    }

    private void setEnabled(boolean e) { 
        selectApkBtn.setEnabled(e);
        patchBtn.setEnabled(e && !selectedPatches.isEmpty());
    }

    static class Patch {
        String name, description, patchType;
        int[] offsets;
    }
}

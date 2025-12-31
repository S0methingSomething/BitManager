package com.bitmanager;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import rikka.shizuku.Shizuku;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int SHIZUKU_CODE = 1;
    private static final String PATCHES_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/";
    
    private TextView status;
    private ProgressBar progress;
    private LinearLayout patchList;
    private Button applyBtn, restoreBtn;
    
    private List<Patch> patches = new ArrayList<>();
    private Set<Integer> selectedPatches = new HashSet<>();
    private String bitlifeVersion;

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
        applyBtn = findViewById(R.id.applyBtn);
        restoreBtn = findViewById(R.id.restoreBtn);
        
        applyBtn.setOnClickListener(v -> applyPatches());
        restoreBtn.setOnClickListener(v -> restore());
        
        checkBitLife();
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
                applyBtn.setEnabled(!selectedPatches.isEmpty());
            });
            cb.setChecked(true);
            selectedPatches.add(i);
            patchList.addView(cb);
        }
        applyBtn.setEnabled(true);
        restoreBtn.setEnabled(true);
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

    private void applyPatches() {
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
                Process p = Shizuku.newProcess(new String[]{"sh", "-c", "cp " + libPath + ".orig " + libPath}, null, null);
                p.waitFor();
                if (p.exitValue() != 0) throw new Exception("No backup found");
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
        if (c == SHIZUKU_CODE && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) applyPatches();
    }

    private String findLib() throws Exception {
        Process p = Shizuku.newProcess(new String[]{"sh", "-c",
            "find /data/app -path '*com.candywriter.bitlife*/lib/arm64*' -name 'libil2cpp.so' 2>/dev/null | head -1"}, null, null);
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String path = r.readLine();
        r.close();
        if (path == null || path.isEmpty()) throw new Exception("libil2cpp.so not found");
        return path;
    }

    private void backup(String libPath) throws Exception {
        Shizuku.newProcess(new String[]{"sh", "-c", "[ ! -f " + libPath + ".orig ] && cp " + libPath + " " + libPath + ".orig"}, null, null).waitFor();
    }

    private void exec(String cmd) throws Exception {
        Process p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        p.waitFor();
        if (p.exitValue() != 0) throw new Exception("Failed: " + cmd);
    }

    private void done(String msg) { runOnUiThread(() -> { status.setText(msg); progress.setVisibility(View.GONE); setEnabled(true); }); }
    private void error(String msg) { runOnUiThread(() -> { status.setText(msg); progress.setVisibility(View.GONE); setEnabled(true); }); }
    private void setEnabled(boolean e) { applyBtn.setEnabled(e); restoreBtn.setEnabled(e); }

    static class Patch {
        String name, description, patchType;
        int[] offsets;
    }
}

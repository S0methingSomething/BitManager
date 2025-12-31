package com.bitmanager;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.bitmanager.patch.*;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity implements Patcher.PatchCallback {
    private static final int PICK_APK = 1;
    private static final int SHIZUKU_CODE = 2;
    
    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout patchList;
    private Button selectApkBtn, patchBtn, installBtn;
    
    private List<Patch> patches = new ArrayList<>();
    private File selectedApk;
    private File patchedApk;
    private StringBuilder logBuilder = new StringBuilder();

    // Shizuku
    private IShellService shellService;
    private final ServiceConnection shellConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            shellService = IShellService.Stub.asInterface(binder);
            log("Shizuku service connected");
            doShizukuInstall();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            shellService = null;
        }
    };
    
    private Shizuku.UserServiceArgs shellArgs;
    
    private final Shizuku.OnRequestPermissionResultListener PERMISSION_LISTENER = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            installWithShizuku();
        } else {
            installStandard();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        
        logView = findViewById(R.id.logView);
        logView.setTextIsSelectable(true);
        logScroll = findViewById(R.id.logScroll);
        patchList = findViewById(R.id.patchList);
        selectApkBtn = findViewById(R.id.selectApkBtn);
        patchBtn = findViewById(R.id.patchBtn);
        installBtn = findViewById(R.id.installBtn);
        
        selectApkBtn.setOnClickListener(v -> pickApk());
        patchBtn.setOnClickListener(v -> startPatching());
        installBtn.setOnClickListener(v -> installApk());
        
        shellArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName()))
            .daemon(false).processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
        
        Shizuku.addRequestPermissionResultListener(PERMISSION_LISTENER);
        log("BitManager ready\nSelect a BitLife APK to patch");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(PERMISSION_LISTENER);
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("application/vnd.android.package-archive");
        startActivityForResult(i, PICK_APK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_APK && res == RESULT_OK && data != null) {
            try {
                selectedApk = copyToCache(data.getData());
                String version = PatchLoader.detectVersion(selectedApk);
                log("Selected: " + selectedApk.getName() + " (v" + version + ")");
                loadPatches(version);
            } catch (Exception e) {
                log("Error: " + e.getMessage());
            }
        }
    }

    private File copyToCache(Uri uri) throws IOException {
        File out = new File(getCacheDir(), "input.apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) fos.write(buf, 0, len);
        }
        return out;
    }

    private void loadPatches(String version) {
        patchList.removeAllViews();
        patches.clear();
        
        try {
            patches = PatchLoader.loadPatches(this, version);
            
            if (patches.isEmpty()) {
                log("No patches found for version " + version);
                return;
            }
            
            log("Loaded " + patches.size() + " patches:");
            for (int i = 0; i < patches.size(); i++) {
                Patch p = patches.get(i);
                CheckBox cb = new CheckBox(this);
                cb.setText(p.name + " (" + p.type + ")");
                cb.setChecked(p.enabled);
                final int idx = i;
                cb.setOnCheckedChangeListener((v, checked) -> patches.get(idx).enabled = checked);
                patchList.addView(cb);
                log("  • " + p.name);
            }
            
            patchBtn.setEnabled(true);
        } catch (Exception e) {
            log("Error loading patches: " + e.getMessage());
        }
    }

    private void startPatching() {
        if (selectedApk == null) {
            log("No APK selected");
            return;
        }
        
        List<Patch> enabledPatches = new ArrayList<>();
        for (Patch p : patches) {
            if (p.enabled) enabledPatches.add(p);
        }
        
        if (enabledPatches.isEmpty()) {
            log("No patches selected");
            return;
        }
        
        patchBtn.setEnabled(false);
        log("\nStarting patch process...");
        
        Patcher patcher = new Patcher(this, selectedApk, getCacheDir(), enabledPatches, this);
        patcher.patch();
    }

    // Patcher.PatchCallback implementation
    @Override
    public void onLog(String message) {
        runOnUiThread(() -> log(message));
    }

    @Override
    public void onProgress(int current, int total) {
        runOnUiThread(() -> log("Progress: " + current + "/" + total));
    }

    @Override
    public void onComplete(File result) {
        patchedApk = result;
        runOnUiThread(() -> {
            log("✓ Patched APK: " + result.getName());
            installBtn.setEnabled(true);
            patchBtn.setEnabled(true);
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            log("✗ Error: " + error);
            patchBtn.setEnabled(true);
        });
    }

    private void installApk() {
        if (patchedApk == null || !patchedApk.exists()) {
            log("No patched APK available");
            return;
        }
        
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                installWithShizuku();
            } else {
                Shizuku.requestPermission(SHIZUKU_CODE);
            }
        } else {
            installStandard();
        }
    }

    private void installWithShizuku() {
        log("Installing via Shizuku...");
        try {
            File tmpApk = new File("/data/local/tmp/bitlife_patched.apk");
            copyFile(patchedApk, tmpApk);
            Shizuku.bindUserService(shellArgs, shellConn);
        } catch (Exception e) {
            log("Shizuku error: " + e.getMessage());
            installStandard();
        }
    }

    private void doShizukuInstall() {
        new Thread(() -> {
            try {
                String result = shellService.exec("pm install -r -i com.android.vending /data/local/tmp/bitlife_patched.apk");
                runOnUiThread(() -> log("Install result: " + result));
            } catch (Exception e) {
                runOnUiThread(() -> log("Install error: " + e.getMessage()));
            } finally {
                Shizuku.unbindUserService(shellArgs, shellConn, true);
            }
        }).start();
    }

    private void installStandard() {
        log("Installing via standard method...");
        Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", patchedApk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) fos.write(buf, 0, len);
        }
    }

    private void log(String msg) {
        logBuilder.append(msg).append("\n");
        logView.setText(logBuilder.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}

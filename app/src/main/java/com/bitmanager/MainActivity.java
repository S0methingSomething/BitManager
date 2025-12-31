package com.bitmanager;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.bitmanager.patcher.*;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity implements Patcher.Callback {
    private static final int PICK_APK = 1;
    private static final int SHIZUKU_CODE = 2;
    
    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout patchListView;
    private Button selectApkBtn, patchBtn, installBtn;
    
    private File selectedApk;
    private String apkVersion;
    private List<Patch> allPatches = new ArrayList<>();
    private List<Patch> selectedPatches = new ArrayList<>();
    private File patchedApk;
    private StringBuilder logBuilder = new StringBuilder();

    // Shizuku
    private IShellService shellService;
    private Shizuku.UserServiceArgs shellArgs;
    private final ServiceConnection shellConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            shellService = IShellService.Stub.asInterface(binder);
            doShizukuInstall();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { shellService = null; }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        
        logView = findViewById(R.id.logView);
        logView.setTextIsSelectable(true);
        logScroll = findViewById(R.id.logScroll);
        patchListView = findViewById(R.id.patchList);
        selectApkBtn = findViewById(R.id.selectApkBtn);
        patchBtn = findViewById(R.id.patchBtn);
        installBtn = findViewById(R.id.installBtn);
        
        selectApkBtn.setOnClickListener(v -> pickApk());
        patchBtn.setOnClickListener(v -> startPatch());
        installBtn.setOnClickListener(v -> installApk());
        
        shellArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName()))
            .daemon(false).processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
        
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code == SHIZUKU_CODE && result == PackageManager.PERMISSION_GRANTED) 
                installWithShizuku();
            else 
                installStandard();
        });
        
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
            patchListView.setVisibility(View.GONE);
            patchBtn.setVisibility(View.GONE);
            log("Loading APK...");
            
            new Thread(() -> {
                try {
                    selectedApk = copyToCache(data.getData());
                    log("✓ APK loaded (" + (selectedApk.length() / 1024 / 1024) + " MB)");
                    detectVersion();
                } catch (Exception e) {
                    log("✗ Failed: " + e.getMessage());
                }
            }).start();
        }
    }

    private File copyToCache(Uri uri) throws IOException {
        File f = new File(getCacheDir(), "selected.apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
        return f;
    }

    private void detectVersion() {
        PackageInfo info = getPackageManager().getPackageArchiveInfo(selectedApk.getAbsolutePath(), 0);
        if (info != null && "com.candywriter.bitlife".equals(info.packageName)) {
            apkVersion = info.versionName;
            log("✓ BitLife v" + apkVersion + " detected");
            fetchPatches();
        } else {
            log("✗ Not a BitLife APK!");
            selectedApk = null;
        }
    }

    private void fetchPatches() {
        log("Fetching patches for v" + apkVersion + "...");
        try {
            allPatches = PatchRepository.fetchPatches(apkVersion);
            for (Patch p : allPatches) {
                log("  • " + p.name + (p.isDex() ? " [DEX]" : ""));
            }
            log("✓ Found " + allPatches.size() + " patches\n");
            runOnUiThread(this::showPatches);
        } catch (Exception e) {
            log("✗ No patches available for v" + apkVersion);
        }
    }

    private void showPatches() {
        patchListView.removeAllViews();
        selectedPatches.clear();
        selectedPatches.addAll(allPatches);
        
        for (int i = 0; i < allPatches.size(); i++) {
            Patch p = allPatches.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setText(p.name + (p.isDex() ? " [DEX]" : ""));
            cb.setTextSize(16);
            cb.setPadding(0, 12, 0, 12);
            cb.setChecked(true);
            
            final Patch patch = p;
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selectedPatches.add(patch);
                else selectedPatches.remove(patch);
                patchBtn.setEnabled(!selectedPatches.isEmpty());
            });
            
            patchListView.addView(cb);
        }
        
        patchListView.setVisibility(View.VISIBLE);
        patchBtn.setVisibility(View.VISIBLE);
        patchBtn.setEnabled(true);
    }

    private void startPatch() {
        patchBtn.setEnabled(false);
        selectApkBtn.setEnabled(false);
        
        Patcher patcher = new Patcher(this, selectedApk, apkVersion, selectedPatches, this);
        patcher.start();
    }

    // Patcher.Callback
    @Override
    public void onLog(String message) { log(message); }
    
    @Override
    public void onComplete(File result) {
        patchedApk = result;
        runOnUiThread(() -> {
            selectApkBtn.setEnabled(true);
            patchBtn.setEnabled(true);
            installBtn.setVisibility(View.VISIBLE);
            installBtn.setEnabled(true);
        });
    }
    
    @Override
    public void onError(String error) {
        log("\n✗ FAILED: " + error);
        runOnUiThread(() -> {
            selectApkBtn.setEnabled(true);
            patchBtn.setEnabled(true);
        });
    }

    // Installation
    private void installApk() {
        if (patchedApk == null) return;
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                installWithShizuku();
            } else if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_CODE);
            } else {
                installStandard();
            }
        } catch (Exception e) { installStandard(); }
    }
    
    private void installWithShizuku() {
        log("Installing via Shizuku...");
        try { Shizuku.bindUserService(shellArgs, shellConn); }
        catch (Exception e) { installStandard(); }
    }
    
    private void doShizukuInstall() {
        new Thread(() -> {
            try {
                String tmp = "/data/local/tmp/bitmanager.apk";
                shellService.exec("cp " + patchedApk.getAbsolutePath() + " " + tmp);
                String result = shellService.exec("pm install -r -i com.android.vending " + tmp);
                log(result);
                shellService.exec("rm " + tmp);
                Shizuku.unbindUserService(shellArgs, shellConn, true);
            } catch (Exception e) { runOnUiThread(this::installStandard); }
        }).start();
    }
    
    private void installStandard() {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", patchedApk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { log("✗ Install failed: " + e.getMessage()); }
    }
}

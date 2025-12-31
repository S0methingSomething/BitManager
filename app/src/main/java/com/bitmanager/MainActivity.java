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
import org.json.*;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;
import java.security.*;
import java.security.cert.*;

public class MainActivity extends Activity {
    private static final int PICK_APK = 1;
    private static final int SHIZUKU_CODE = 2;
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
    private static final byte[] RETURN_VOID = {(byte)0xC0, 0x03, 0x5F, (byte)0xD6};

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
        if (requestCode == SHIZUKU_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                installWithShizuku();
            } else {
                installStandard();
            }
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
        patchBtn.setOnClickListener(v -> patchApk());
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
                patch.description = p.optString("description", "");
                patch.type = p.optString("type", "native");
                patch.patchType = p.getString("patch");
                
                if ("dex".equals(patch.type)) {
                    patch.dexFile = p.getString("dexFile");
                    patch.className = p.getString("className");
                    patch.methodName = p.getString("methodName");
                } else {
                    JSONArray offsets = p.getJSONArray("offsets");
                    patch.offsets = new long[offsets.length()];
                    for (int j = 0; j < offsets.length(); j++) {
                        patch.offsets[j] = Long.decode(offsets.getString(j));
                    }
                }
                patches.add(patch);
                log("  • " + patch.name);
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
            cb.setText(p.name + (p.type.equals("dex") ? " [DEX]" : ""));
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
                
                log("[1/4] Extracting APK...");
                File extractDir = new File(getCacheDir(), "apk_extract");
                deleteRecursive(extractDir);
                extractDir.mkdirs();
                unzip(selectedApk, extractDir);
                deleteRecursive(new File(extractDir, "META-INF"));
                log("✓ Extracted\n");

                log("[2/4] Applying patches...");
                int totalPatched = 0;
                
                for (int idx : selectedPatches) {
                    Patch p = patches.get(idx);
                    
                    if ("dex".equals(p.type)) {
                        // DEX patch
                        File dexFile = new File(extractDir, p.dexFile);
                        if (!dexFile.exists()) {
                            log("  ✗ " + p.name + " - DEX not found");
                            continue;
                        }
                        applyDexPatch(dexFile, p);
                        log("  ✓ " + p.name + " [DEX]");
                        totalPatched++;
                    } else {
                        // Native patch
                        File libFile = findLib(extractDir);
                        if (libFile == null) {
                            log("  ✗ " + p.name + " - lib not found");
                            continue;
                        }
                        byte[] patch = getPatchBytes(p.patchType);
                        try (RandomAccessFile raf = new RandomAccessFile(libFile, "rw")) {
                            for (long off : p.offsets) {
                                raf.seek(off);
                                raf.write(patch);
                            }
                        }
                        log("  ✓ " + p.name + " (" + p.offsets.length + " offsets)");
                        totalPatched += p.offsets.length;
                    }
                }
                log("✓ Applied " + totalPatched + " patches\n");

                log("[3/4] Repacking APK...");
                File unsigned = new File(getCacheDir(), "unsigned.apk");
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsigned))) {
                    zipDir(extractDir, extractDir, zos);
                }
                log("✓ Repacked\n");

                log("[4/4] Signing APK...");
                patchedApk = new File(getExternalFilesDir(null), "BitLife_v" + apkVersion + "_patched.apk");
                signApk(unsigned, patchedApk);
                unsigned.delete();
                deleteRecursive(extractDir);
                log("✓ Signed: " + (patchedApk.length() / 1024 / 1024) + " MB\n");

                log("═══════════════════════════════");
                log("✓ PATCHING COMPLETE!\n");
                
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

    private byte[] getPatchBytes(String type) {
        switch (type) {
            case "return_true": return RETURN_TRUE;
            case "return_false": return RETURN_FALSE;
            case "return_void": return RETURN_VOID;
            default: return RETURN_TRUE;
        }
    }

    private void applyDexPatch(File dexFile, Patch patch) throws Exception {
        byte[] dex = readFile(dexFile);
        int codeOffset = findMethodCodeOffset(dex, patch.className, patch.methodName);
        if (codeOffset == -1) throw new Exception("Method not found: " + patch.methodName);
        
        // Insert return-void at start of method
        dex[codeOffset] = 0x0e;     // return-void opcode
        dex[codeOffset + 1] = 0x00;
        
        updateDexChecksums(dex);
        writeFile(dexFile, dex);
    }

    private int findMethodCodeOffset(byte[] dex, String className, String methodName) {
        int stringIdsOff = readInt(dex, 0x3C);
        int stringIdsSize = readInt(dex, 0x38);
        int typeIdsOff = readInt(dex, 0x44);
        int typeIdsSize = readInt(dex, 0x40);
        int methodIdsOff = readInt(dex, 0x5C);
        int classDefsOff = readInt(dex, 0x64);
        int classDefsSize = readInt(dex, 0x60);
        
        String[] strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            int strOff = readInt(dex, stringIdsOff + i * 4);
            strings[i] = readMutf8(dex, strOff);
        }
        
        int[] typeIds = new int[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            typeIds[i] = readInt(dex, typeIdsOff + i * 4);
        }
        
        int targetClassIdx = -1;
        for (int i = 0; i < typeIdsSize; i++) {
            if (strings[typeIds[i]].equals(className)) {
                targetClassIdx = i;
                break;
            }
        }
        if (targetClassIdx == -1) return -1;
        
        for (int i = 0; i < classDefsSize; i++) {
            int classDefOff = classDefsOff + i * 32;
            if (readInt(dex, classDefOff) != targetClassIdx) continue;
            
            int classDataOff = readInt(dex, classDefOff + 24);
            if (classDataOff == 0) continue;
            
            int[] pos = {classDataOff};
            int staticFields = readUleb128(dex, pos);
            int instanceFields = readUleb128(dex, pos);
            int directMethods = readUleb128(dex, pos);
            int virtualMethods = readUleb128(dex, pos);
            
            for (int j = 0; j < staticFields + instanceFields; j++) {
                readUleb128(dex, pos);
                readUleb128(dex, pos);
            }
            
            int methodIdx = 0;
            for (int j = 0; j < directMethods + virtualMethods; j++) {
                methodIdx += readUleb128(dex, pos);
                readUleb128(dex, pos);
                int codeOff = readUleb128(dex, pos);
                
                int nameIdx = readShort(dex, methodIdsOff + methodIdx * 8 + 4);
                if (strings[nameIdx].equals(methodName) && codeOff != 0) {
                    return codeOff + 16;
                }
            }
        }
        return -1;
    }

    private void updateDexChecksums(byte[] dex) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(dex, 32, dex.length - 32);
        System.arraycopy(sha1.digest(), 0, dex, 12, 20);
        
        java.util.zip.Adler32 adler = new java.util.zip.Adler32();
        adler.update(dex, 12, dex.length - 12);
        writeInt(dex, 8, (int) adler.getValue());
    }

    private String readMutf8(byte[] data, int off) {
        while ((data[off] & 0x80) != 0) off++;
        off++;
        StringBuilder sb = new StringBuilder();
        while (data[off] != 0) {
            int b = data[off++] & 0xFF;
            if ((b & 0x80) == 0) sb.append((char) b);
            else if ((b & 0xE0) == 0xC0) sb.append((char) (((b & 0x1F) << 6) | (data[off++] & 0x3F)));
            else { sb.append((char) (((b & 0x0F) << 12) | ((data[off++] & 0x3F) << 6) | (data[off++] & 0x3F))); }
        }
        return sb.toString();
    }

    private int readUleb128(byte[] data, int[] pos) {
        int result = 0, shift = 0;
        while (true) {
            int b = data[pos[0]++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private int readInt(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);
    }

    private int readShort(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8);
    }

    private void writeInt(byte[] d, int o, int v) {
        d[o]=(byte)v; d[o+1]=(byte)(v>>8); d[o+2]=(byte)(v>>16); d[o+3]=(byte)(v>>24);
    }

    private byte[] readFile(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) { fis.read(data); }
        return data;
    }

    private void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
    }

    private File findLib(File dir) {
        File lib = new File(dir, "lib/arm64-v8a/libil2cpp.so");
        if (lib.exists()) return lib;
        lib = new File(dir, "lib/armeabi-v7a/libil2cpp.so");
        return lib.exists() ? lib : null;
    }

    private void installApk() {
        if (patchedApk == null || !patchedApk.exists()) return;
        
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                installWithShizuku();
            } else if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_CODE);
            } else {
                installStandard();
            }
        } catch (Exception e) {
            installStandard();
        }
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
            } catch (Exception e) {
                runOnUiThread(this::installStandard);
            }
        }).start();
    }
    
    private void installStandard() {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", patchedApk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
                if (entry.isDirectory()) f.mkdirs();
                else {
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

    private void zipDir(File root, File dir, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) { zipDir(root, f, zos); continue; }
            String path = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            ZipEntry entry = new ZipEntry(path);
            if (path.equals("resources.arsc") || path.endsWith(".so") || path.endsWith(".png")) {
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(f.length());
                entry.setCompressedSize(f.length());
                entry.setCrc(computeCrc(f));
            }
            zos.putNextEntry(entry);
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
            }
            zos.closeEntry();
        }
    }
    
    private long computeCrc(File f) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) crc.update(buf, 0, len);
        }
        return crc.getValue();
    }

    private void signApk(File input, File output) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = getAssets().open("debug.p12")) {
            ks.load(is, "android".toCharArray());
        }
        PrivateKey pk = (PrivateKey) ks.getKey("key", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("key");
        
        new com.android.apksig.ApkSigner.Builder(Collections.singletonList(
            new com.android.apksig.ApkSigner.SignerConfig.Builder("BitManager", pk, Collections.singletonList(cert)).build()))
            .setInputApk(input).setOutputApk(output)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).build().sign();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        f.delete();
    }

    static class Patch {
        String name, description, type, patchType;
        String dexFile, className, methodName; // for DEX patches
        long[] offsets; // for native patches
    }
}

package com.bitmanager.patcher;

import android.content.Context;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import java.io.File;
import java.util.List;

/**
 * Orchestrates APK patching using Python backend.
 */
public class Patcher extends Thread {
    
    public interface Callback {
        void onLog(String message);
        void onComplete(File result);
        void onError(String error);
    }
    
    private final Context ctx;
    private final File inputApk;
    private final String version;
    private final Callback callback;
    
    public Patcher(Context ctx, File inputApk, String version, List<Patch> patches, Callback callback) {
        this.ctx = ctx;
        this.inputApk = inputApk;
        this.version = version;
        this.callback = callback;
    }
    
    @Override
    public void run() {
        try {
            File outputApk = new File(ctx.getCacheDir(), "patched.apk");
            File signedApk = new File(ctx.getCacheDir(), "signed.apk");
            
            // Call Python patcher
            Python py = Python.getInstance();
            PyObject patcher = py.getModule("patcher");
            
            patcher.callAttr("patch_apk",
                inputApk.getAbsolutePath(),
                outputApk.getAbsolutePath(),
                version,
                (PyObject.FromJava) msg -> {
                    callback.onLog(msg.toString());
                    return null;
                }
            );
            
            // Sign with Java (apksig)
            callback.onLog("Signing APK...");
            ApkUtils.sign(ctx, outputApk, signedApk);
            outputApk.delete();
            
            callback.onLog("✓ Patching complete!");
            callback.onComplete(signedApk);
            
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }
}

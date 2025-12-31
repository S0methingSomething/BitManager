package com.bitmanager.patch;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

public class PatchLoader {
    public static List<Patch> loadPatches(Context ctx, String version) throws Exception {
        List<Patch> patches = new ArrayList<>();
        
        // Try to load from assets first
        String filename = "patches_" + version + ".json";
        InputStream is = null;
        
        try {
            is = ctx.getAssets().open(filename);
        } catch (IOException e) {
            // Try generic patches file
            try {
                is = ctx.getAssets().open("patches.json");
            } catch (IOException e2) {
                return patches;
            }
        }
        
        String json = readStream(is);
        is.close();
        
        JSONObject root = new JSONObject(json);
        JSONArray patchArray = root.optJSONArray("patches");
        if (patchArray == null) return patches;
        
        for (int i = 0; i < patchArray.length(); i++) {
            JSONObject p = patchArray.getJSONObject(i);
            Patch patch = new Patch();
            
            patch.name = p.getString("name");
            patch.description = p.optString("description", "");
            patch.type = p.getString("type");
            patch.enabled = p.optBoolean("enabled", true);
            
            if ("dex".equals(patch.type)) {
                patch.dexFile = p.getString("dexFile");
                patch.className = p.getString("className");
                patch.methodName = p.getString("methodName");
                patch.methodSig = p.optString("methodSig", "");
                patch.dexPatchType = p.getString("patchType");
            } else if ("native".equals(patch.type)) {
                patch.targetFile = p.getString("targetFile");
                patch.offset = p.getLong("offset");
                patch.patchType = p.getString("patchType");
                
                JSONArray customBytesArr = p.optJSONArray("customBytes");
                if (customBytesArr != null) {
                    patch.customBytes = new byte[customBytesArr.length()];
                    for (int j = 0; j < customBytesArr.length(); j++) {
                        patch.customBytes[j] = (byte) customBytesArr.getInt(j);
                    }
                }
            }
            
            patches.add(patch);
        }
        
        return patches;
    }
    
    public static String detectVersion(File apk) throws IOException {
        // Read version from APK manifest
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            // Simple approach: look for version in filename or use androguard-style parsing
            // For now, return a default
            String name = apk.getName().toLowerCase();
            if (name.contains("3.21")) return "3.21.4";
            if (name.contains("3.22")) return "3.22";
            return "unknown";
        }
    }
    
    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toString("UTF-8");
    }
}

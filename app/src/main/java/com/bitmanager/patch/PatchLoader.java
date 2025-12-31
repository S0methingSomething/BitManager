package com.bitmanager.patch;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

public class PatchLoader {
    public static List<Patch> loadPatches(Context ctx, String version) throws Exception {
        List<Patch> patches = new ArrayList<>();
        
        // Try version-specific, then fallback
        String[] files = {"patches_" + version + ".json", "patches_3.21.4.json", "patches.json"};
        InputStream is = null;
        
        for (String f : files) {
            try { is = ctx.getAssets().open(f); break; } catch (IOException e) {}
        }
        if (is == null) return patches;
        
        String json = readStream(is);
        is.close();
        
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("patches");
        if (arr == null) return patches;
        
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
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
            }
            patches.add(patch);
        }
        return patches;
    }
    
    public static String detectVersion(File apk) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            // Try reading version from classes.dex string table or manifest
            java.util.zip.ZipEntry dex = zip.getEntry("classes.dex");
            if (dex != null) {
                byte[] data = readBytes(zip.getInputStream(dex));
                // Search for BitLife version string pattern in DEX
                String ver = findVersionInData(data);
                if (ver != null) return ver;
            }
        }
        return "3.21.4"; // Default fallback
    }
    
    private static String findVersionInData(byte[] data) {
        // Search for "3.xx.x" or "3.xx" pattern
        for (int i = 0; i < data.length - 6; i++) {
            if (data[i] == '3' && data[i+1] == '.') {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(i + 10, data.length); j++) {
                    char c = (char) data[j];
                    if (Character.isDigit(c) || c == '.') sb.append(c);
                    else break;
                }
                String ver = sb.toString();
                if (ver.matches("3\\.\\d+\\.?\\d*") && ver.length() >= 4) {
                    return ver;
                }
            }
        }
        return null;
    }
    
    private static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        return bos.toByteArray();
    }
    
    private static String readStream(InputStream is) throws IOException {
        return new String(readBytes(is), "UTF-8");
    }
}

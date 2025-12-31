package com.bitmanager.patcher;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Fetches patches from GitHub repository.
 */
public class PatchRepository {
    private static final String BASE_URL = "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/";
    
    public static List<Patch> fetchPatches(String version) throws Exception {
        String json = fetch(BASE_URL + version + ".json");
        return parsePatches(json);
    }
    
    private static List<Patch> parsePatches(String json) throws JSONException {
        List<Patch> patches = new ArrayList<>();
        JSONObject obj = new JSONObject(json);
        JSONArray arr = obj.getJSONArray("patches");
        
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            Patch patch = new Patch();
            patch.name = p.getString("name");
            patch.description = p.optString("description", "");
            patch.type = p.optString("type", "native");
            patch.patchType = p.getString("patch");
            
            if (patch.isDex()) {
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
        }
        return patches;
    }
    
    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}

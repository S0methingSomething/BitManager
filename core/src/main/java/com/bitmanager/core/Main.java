package com.bitmanager.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * CLI entry point - java -jar core.jar input.apk [options]
 */
public class Main {
    
    private static final String PATCHES_URL = 
        "https://raw.githubusercontent.com/S0methingSomething/BitManager/main/patches/%s.json";
    
    public static void main(String[] args) {
        if (args.length < 1 || args[0].equals("-h") || args[0].equals("--help")) {
            printHelp();
            return;
        }
        
        // Parse args
        String inputApk = args[0];
        String outputApk = inputApk.replace(".apk", "_patched.apk");
        String version = null;
        String keystore = findDefaultKeystore();
        boolean corex = false;
        boolean noPatches = false;
        boolean useBsdiff = false;
        
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--version": version = args[++i]; break;
                case "--keystore": keystore = args[++i]; break;
                case "--corex": corex = true; break;
                case "--bsdiff": useBsdiff = true; break;
                case "--no-patches": noPatches = true; break;
                case "-o": outputApk = args[++i]; break;
                default:
                    if (!args[i].startsWith("-")) outputApk = args[i];
            }
        }
        
        // Detect version if not specified
        if (version == null) {
            version = detectVersion(inputApk);
        }
        if (version == null) {
            System.err.println("[✗] Could not detect version. Use --version X.X.X");
            System.exit(1);
        }
        System.out.println("[✓] Version: " + version);
        
        // Fetch patches
        List<Patcher.NativePatch> patches = new ArrayList<>();
        if (!noPatches) {
            patches = fetchPatches(version);
            System.out.println("[✓] Found " + patches.size() + " patches");
        }
        
        // Create config
        Patcher.PatchConfig config = new Patcher.PatchConfig();
        config.keystore = keystore;
        config.patches = patches;
        
        // Find bsdiff patch if requested
        if (useBsdiff) {
            File bsdiff = findBsdiffPatch(version);
            if (bsdiff != null) {
                config.bsdiffPatch = bsdiff;
                System.out.println("[✓] Using bsdiff patch: " + bsdiff.getName());
            } else {
                System.out.println("[*] No bsdiff patch for version " + version);
            }
        }
        
        // Patch
        Patcher patcher = new Patcher(null);
        try {
            boolean success = patcher.patch(new File(inputApk), new File(outputApk), config);
            System.exit(success ? 0 : 1);
        } catch (Exception e) {
            System.err.println("[✗] Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void printHelp() {
        System.out.println("BitManager Core Patcher\n");
        System.out.println("Usage: java -jar core.jar <input.apk> [output.apk] [options]\n");
        System.out.println("Options:");
        System.out.println("  --version X.X.X   Specify APK version");
        System.out.println("  --keystore PATH   Custom keystore");
        System.out.println("  --bsdiff          Apply bsdiff patch (restores pairip code)");
        System.out.println("  --corex           Enable CoreX pairip bypass (alternative)");
        System.out.println("  --no-patches      Skip JSON patches");
        System.out.println("  -o OUTPUT         Output APK path");
    }
    
    private static File findBsdiffPatch(String version) {
        String[] paths = {
            "patches/bsdiff/" + version + ".bsdiff",
            "../patches/bsdiff/" + version + ".bsdiff"
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) return f;
        }
        return null;
    }
    
    private static String findDefaultKeystore() {
        // Check common locations
        String[] paths = {
            "debug.keystore",
            System.getProperty("user.home") + "/.android/debug.keystore",
            "../cli/debug.keystore"
        };
        for (String p : paths) {
            if (new File(p).exists()) return p;
        }
        return "debug.keystore";
    }
    
    private static String detectVersion(String apkPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("aapt", "dump", "badging", apkPath);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("versionName=")) {
                        int start = line.indexOf("versionName='") + 13;
                        int end = line.indexOf("'", start);
                        return line.substring(start, end);
                    }
                }
            }
        } catch (Exception ignored) {}
        
        // Fallback: extract from filename
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.?\\d*)").matcher(apkPath);
        return m.find() ? m.group(1) : null;
    }
    
    private static List<Patcher.NativePatch> fetchPatches(String version) {
        List<Patcher.NativePatch> patches = new ArrayList<>();
        
        // Try local file first
        File local = new File("patches/" + version + ".json");
        if (!local.exists()) local = new File("../patches/" + version + ".json");
        
        String json = null;
        if (local.exists()) {
            try {
                json = Files.readString(local.toPath());
                System.out.println("[*] Using local patches: " + local);
            } catch (Exception ignored) {}
        }
        
        // Fetch from URL
        if (json == null) {
            try {
                URL url = new URL(String.format(PATCHES_URL, version));
                System.out.println("[*] Fetching patches from " + url);
                try (InputStream is = url.openStream()) {
                    json = new String(is.readAllBytes());
                }
            } catch (Exception e) {
                System.out.println("[*] No patches found for version " + version);
                return patches;
            }
        }
        
        // Parse JSON
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("patches");
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("offsets")) {
                    Patcher.NativePatch patch = new Patcher.NativePatch();
                    patch.name = obj.has("name") ? obj.get("name").getAsString() : "patch";
                    patch.offsets = new ArrayList<>();
                    for (JsonElement off : obj.getAsJsonArray("offsets")) {
                        patch.offsets.add(off.getAsString());
                    }
                    patches.add(patch);
                }
            }
        } catch (Exception e) {
            System.err.println("[*] Error parsing patches: " + e.getMessage());
        }
        
        return patches;
    }
}

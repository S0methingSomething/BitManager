package com.bitmanager.patcher;

/**
 * Represents a patch that can be applied to an APK.
 * Supports both native (.so) and DEX patches.
 */
public class Patch {
    public String name;
    public String description;
    public String type = "native";  // "native" or "dex"
    public String patch;            // "return_void", "return_true", "return_false"
    
    // For native patches
    public String[] offsets;
    
    // For DEX patches (pre-computed offset)
    public String dexFile;
    public String offset;           // Pre-computed offset in hex
    public String bytes;            // Bytes to write in hex
    
    public boolean isNative() { return "native".equals(type); }
    public boolean isDex() { return "dex".equals(type); }
}

package com.bitmanager.patcher;

/**
 * Represents a patch that can be applied to an APK.
 * Supports both native (.so) and DEX patches.
 */
public class Patch {
    public String name;
    public String description;
    public String type = "native";  // "native" or "dex"
    public String patchType;        // "return_void", "return_true", "return_false"
    
    // For native patches
    public long[] offsets;
    
    // For DEX patches  
    public String dexFile;
    public String className;
    public String methodName;
    
    public boolean isNative() { return "native".equals(type); }
    public boolean isDex() { return "dex".equals(type); }
}

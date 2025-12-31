package com.bitmanager.patch;

public class Patch {
    public String name;
    public String description;
    public String type; // "dex" or "native"
    public boolean enabled = true;
    
    // For DEX patches
    public String dexFile;      // e.g., "classes11.dex"
    public String className;    // e.g., "Lcom/pairip/SignatureCheck;"
    public String methodName;   // e.g., "verifyIntegrity"
    public String methodSig;    // e.g., "(Landroid/content/Context;)V"
    public String dexPatchType; // "return_void", "return_true", "return_false"
    
    // For native patches
    public String targetFile;   // e.g., "lib/arm64-v8a/libil2cpp.so"
    public long offset;
    public String patchType;    // "return_true", "return_false", "return_void", "nop"
    public byte[] customBytes;
}

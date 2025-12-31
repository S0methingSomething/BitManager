package com.bitmanager.patcher;

import java.io.*;

/**
 * Applies patches to native .so files.
 */
public class NativePatcher {
    private static final byte[] RETURN_TRUE = {0x20, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_FALSE = {0x00, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_VOID = {(byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    
    public static int apply(File libFile, Patch patch) throws IOException {
        byte[] patchBytes = getPatchBytes(patch.patchType);
        int count = 0;
        
        try (RandomAccessFile raf = new RandomAccessFile(libFile, "rw")) {
            for (long offset : patch.offsets) {
                raf.seek(offset);
                raf.write(patchBytes);
                count++;
            }
        }
        return count;
    }
    
    private static byte[] getPatchBytes(String type) {
        switch (type) {
            case "return_true": return RETURN_TRUE;
            case "return_false": return RETURN_FALSE;
            case "return_void": return RETURN_VOID;
            default: return RETURN_VOID;
        }
    }
}

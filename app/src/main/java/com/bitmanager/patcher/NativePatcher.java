package com.bitmanager.patcher;

import java.io.*;

/**
 * Applies patches to native .so files.
 */
public class NativePatcher {
    private static final byte[] RETURN_TRUE = {0x20, 0x00, (byte)0x80, (byte)0xD2, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_FALSE = {0x00, 0x00, (byte)0x80, (byte)0xD2, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_VOID = {(byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    
    public static int apply(File libFile, Patch patch) throws IOException {
        byte[] patchBytes = getPatchBytes(patch.patch);
        int count = 0;
        
        try (RandomAccessFile raf = new RandomAccessFile(libFile, "rw")) {
            for (String offsetStr : patch.offsets) {
                long offset = Long.parseLong(offsetStr.replace("0x", ""), 16);
                raf.seek(offset);
                raf.write(patchBytes);
                count++;
            }
        }
        return count;
    }
    
    private static byte[] getPatchBytes(String type) {
        if (type == null) return RETURN_VOID;
        switch (type) {
            case "return_true": return RETURN_TRUE;
            case "return_false": return RETURN_FALSE;
            default: return RETURN_VOID;
        }
    }
}

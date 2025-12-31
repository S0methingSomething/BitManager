package com.bitmanager.patch;

import java.io.*;

public class NativePatcher {
    // ARM64 instruction patterns
    private static final byte[] RETURN_TRUE = {0x20, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_FALSE = {0x00, 0x00, (byte)0x80, 0x52, (byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] RETURN_VOID = {(byte)0xC0, 0x03, 0x5F, (byte)0xD6};
    private static final byte[] NOP = {0x1F, 0x20, 0x03, (byte)0xD5};
    
    public static void applyPatch(File targetFile, Patch patch) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
            raf.seek(patch.offset);
            
            byte[] patchBytes;
            if (patch.customBytes != null) {
                patchBytes = patch.customBytes;
            } else {
                switch (patch.patchType) {
                    case "return_true":
                        patchBytes = RETURN_TRUE;
                        break;
                    case "return_false":
                        patchBytes = RETURN_FALSE;
                        break;
                    case "return_void":
                        patchBytes = RETURN_VOID;
                        break;
                    case "nop":
                        patchBytes = NOP;
                        break;
                    default:
                        throw new IOException("Unknown patch type: " + patch.patchType);
                }
            }
            
            raf.write(patchBytes);
        }
    }
}

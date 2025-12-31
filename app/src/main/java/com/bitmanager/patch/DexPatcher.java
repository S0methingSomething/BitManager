package com.bitmanager.patch;

import java.io.*;
import java.util.*;

public class DexPatcher {
    // DEX file format constants
    private static final byte[] DEX_MAGIC = {0x64, 0x65, 0x78, 0x0a}; // "dex\n"
    private static final int HEADER_SIZE = 0x70;
    private static final int STRING_IDS_OFF = 0x3C;
    private static final int TYPE_IDS_OFF = 0x44;
    private static final int METHOD_IDS_OFF = 0x5C;
    private static final int CLASS_DEFS_OFF = 0x64;
    
    // Dalvik opcodes
    private static final byte RETURN_VOID = 0x0e;
    private static final byte CONST_4 = 0x12;
    private static final byte RETURN = 0x0f;
    
    public static void applyPatch(File dexFile, Patch patch) throws IOException {
        byte[] dex = readFile(dexFile);
        
        // Find the method and patch it
        int methodOffset = findMethodCodeOffset(dex, patch.className, patch.methodName, patch.methodSig);
        if (methodOffset == -1) {
            throw new IOException("Method not found: " + patch.className + "->" + patch.methodName);
        }
        
        // Apply the patch based on type
        switch (patch.dexPatchType) {
            case "return_void":
                // Insert return-void at start of method
                insertReturnVoid(dex, methodOffset);
                break;
            case "return_true":
                // const/4 v0, 1; return v0
                insertReturnBoolean(dex, methodOffset, true);
                break;
            case "return_false":
                // const/4 v0, 0; return v0
                insertReturnBoolean(dex, methodOffset, false);
                break;
        }
        
        // Update DEX checksum and signature
        updateDexChecksums(dex);
        
        writeFile(dexFile, dex);
    }
    
    private static int findMethodCodeOffset(byte[] dex, String className, String methodName, String methodSig) {
        // Read header info
        int stringIdsOff = readInt(dex, STRING_IDS_OFF);
        int stringIdsSize = readInt(dex, STRING_IDS_OFF - 4);
        int typeIdsOff = readInt(dex, TYPE_IDS_OFF);
        int typeIdsSize = readInt(dex, TYPE_IDS_OFF - 4);
        int methodIdsOff = readInt(dex, METHOD_IDS_OFF);
        int methodIdsSize = readInt(dex, METHOD_IDS_OFF - 4);
        int classDefsOff = readInt(dex, CLASS_DEFS_OFF);
        int classDefsSize = readInt(dex, CLASS_DEFS_OFF - 4);
        
        // Build string table
        String[] strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            int strOff = readInt(dex, stringIdsOff + i * 4);
            strings[i] = readMutf8(dex, strOff);
        }
        
        // Build type table
        int[] typeIds = new int[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            typeIds[i] = readInt(dex, typeIdsOff + i * 4);
        }
        
        // Find target class
        int targetClassIdx = -1;
        for (int i = 0; i < typeIdsSize; i++) {
            if (strings[typeIds[i]].equals(className)) {
                targetClassIdx = i;
                break;
            }
        }
        if (targetClassIdx == -1) return -1;
        
        // Find class def
        for (int i = 0; i < classDefsSize; i++) {
            int classDefOff = classDefsOff + i * 32;
            int classIdx = readInt(dex, classDefOff);
            if (classIdx != targetClassIdx) continue;
            
            int classDataOff = readInt(dex, classDefOff + 24);
            if (classDataOff == 0) continue;
            
            // Parse class_data_item
            int[] pos = {classDataOff};
            int staticFieldsSize = readUleb128(dex, pos);
            int instanceFieldsSize = readUleb128(dex, pos);
            int directMethodsSize = readUleb128(dex, pos);
            int virtualMethodsSize = readUleb128(dex, pos);
            
            // Skip fields
            for (int j = 0; j < staticFieldsSize + instanceFieldsSize; j++) {
                readUleb128(dex, pos); // field_idx_diff
                readUleb128(dex, pos); // access_flags
            }
            
            // Search methods
            int methodIdx = 0;
            for (int j = 0; j < directMethodsSize + virtualMethodsSize; j++) {
                int methodIdxDiff = readUleb128(dex, pos);
                methodIdx += methodIdxDiff;
                int accessFlags = readUleb128(dex, pos);
                int codeOff = readUleb128(dex, pos);
                
                // Check if this is our target method
                int methodIdOff = methodIdsOff + methodIdx * 8;
                int nameIdx = readShort(dex, methodIdOff + 4);
                int protoIdx = readShort(dex, methodIdOff + 6);
                
                if (strings[nameIdx].equals(methodName)) {
                    // Found it! Return the code offset
                    // code_item starts with registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size
                    // insns start at codeOff + 16
                    if (codeOff != 0) {
                        return codeOff + 16; // Skip to insns
                    }
                }
            }
        }
        return -1;
    }
    
    private static void insertReturnVoid(byte[] dex, int codeOffset) {
        // Simply overwrite first instruction with return-void (0e 00)
        dex[codeOffset] = RETURN_VOID;
        dex[codeOffset + 1] = 0x00;
    }
    
    private static void insertReturnBoolean(byte[] dex, int codeOffset, boolean value) {
        // const/4 v0, #int (0 or 1) - opcode 12, format 11n
        // return v0 - opcode 0f, format 11x
        dex[codeOffset] = CONST_4;
        dex[codeOffset + 1] = (byte)(value ? 0x10 : 0x00); // v0 = 1 or 0
        dex[codeOffset + 2] = RETURN;
        dex[codeOffset + 3] = 0x00; // v0
    }
    
    private static void updateDexChecksums(byte[] dex) {
        // Update SHA-1 signature (offset 12, 20 bytes)
        try {
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            sha1.update(dex, 32, dex.length - 32);
            byte[] sig = sha1.digest();
            System.arraycopy(sig, 0, dex, 12, 20);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        // Update Adler32 checksum (offset 8, 4 bytes)
        java.util.zip.Adler32 adler = new java.util.zip.Adler32();
        adler.update(dex, 12, dex.length - 12);
        int checksum = (int) adler.getValue();
        writeInt(dex, 8, checksum);
    }
    
    private static String readMutf8(byte[] data, int off) {
        // Skip ULEB128 length
        while ((data[off] & 0x80) != 0) off++;
        off++;
        
        StringBuilder sb = new StringBuilder();
        while (data[off] != 0) {
            int b = data[off++] & 0xFF;
            if ((b & 0x80) == 0) {
                sb.append((char) b);
            } else if ((b & 0xE0) == 0xC0) {
                int b2 = data[off++] & 0xFF;
                sb.append((char) (((b & 0x1F) << 6) | (b2 & 0x3F)));
            } else {
                int b2 = data[off++] & 0xFF;
                int b3 = data[off++] & 0xFF;
                sb.append((char) (((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)));
            }
        }
        return sb.toString();
    }
    
    private static int readUleb128(byte[] data, int[] pos) {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = data[pos[0]++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }
    
    private static int readInt(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8) |
               ((data[off+2] & 0xFF) << 16) | ((data[off+3] & 0xFF) << 24);
    }
    
    private static int readShort(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8);
    }
    
    private static void writeInt(byte[] data, int off, int val) {
        data[off] = (byte) val;
        data[off+1] = (byte) (val >> 8);
        data[off+2] = (byte) (val >> 16);
        data[off+3] = (byte) (val >> 24);
    }
    
    private static byte[] readFile(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            fis.read(data);
        }
        return data;
    }
    
    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }
}

package com.bitmanager.patcher;

import java.io.*;
import java.security.MessageDigest;
import java.util.zip.Adler32;

/**
 * Applies patches to DEX files by modifying method bytecode.
 */
public class DexPatcher {
    
    public static void apply(File dexFile, Patch patch) throws Exception {
        byte[] dex = readFile(dexFile);
        
        int codeOffset = findMethodCodeOffset(dex, patch.className, patch.methodName);
        if (codeOffset == -1) {
            throw new Exception("Method not found: " + patch.className + "->" + patch.methodName);
        }
        
        // Insert return-void (0x0e 0x00) at start of method
        dex[codeOffset] = 0x0e;
        dex[codeOffset + 1] = 0x00;
        
        updateChecksums(dex);
        writeFile(dexFile, dex);
    }
    
    private static int findMethodCodeOffset(byte[] dex, String className, String methodName) {
        int stringIdsOff = readInt(dex, 0x3C);
        int stringIdsSize = readInt(dex, 0x38);
        int typeIdsOff = readInt(dex, 0x44);
        int typeIdsSize = readInt(dex, 0x40);
        int methodIdsOff = readInt(dex, 0x5C);
        int classDefsOff = readInt(dex, 0x64);
        int classDefsSize = readInt(dex, 0x60);
        
        // Build string table
        String[] strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            strings[i] = readMutf8(dex, readInt(dex, stringIdsOff + i * 4));
        }
        
        // Build type table
        int[] typeIds = new int[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            typeIds[i] = readInt(dex, typeIdsOff + i * 4);
        }
        
        // Find target class index
        int targetClassIdx = -1;
        for (int i = 0; i < typeIdsSize; i++) {
            if (strings[typeIds[i]].equals(className)) {
                targetClassIdx = i;
                break;
            }
        }
        if (targetClassIdx == -1) return -1;
        
        // Search class definitions
        for (int i = 0; i < classDefsSize; i++) {
            int classDefOff = classDefsOff + i * 32;
            if (readInt(dex, classDefOff) != targetClassIdx) continue;
            
            int classDataOff = readInt(dex, classDefOff + 24);
            if (classDataOff == 0) continue;
            
            // Parse class_data_item
            int[] pos = {classDataOff};
            int staticFields = readUleb128(dex, pos);
            int instanceFields = readUleb128(dex, pos);
            int directMethods = readUleb128(dex, pos);
            int virtualMethods = readUleb128(dex, pos);
            
            // Skip fields
            for (int j = 0; j < staticFields + instanceFields; j++) {
                readUleb128(dex, pos);
                readUleb128(dex, pos);
            }
            
            // Search methods
            int methodIdx = 0;
            for (int j = 0; j < directMethods + virtualMethods; j++) {
                methodIdx += readUleb128(dex, pos);
                readUleb128(dex, pos); // access_flags
                int codeOff = readUleb128(dex, pos);
                
                int nameIdx = readShort(dex, methodIdsOff + methodIdx * 8 + 4);
                if (strings[nameIdx].equals(methodName) && codeOff != 0) {
                    return codeOff + 16; // Skip code_item header to insns
                }
            }
        }
        return -1;
    }
    
    private static void updateChecksums(byte[] dex) throws Exception {
        // SHA-1 signature (offset 12, 20 bytes)
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(dex, 32, dex.length - 32);
        System.arraycopy(sha1.digest(), 0, dex, 12, 20);
        
        // Adler32 checksum (offset 8, 4 bytes)
        Adler32 adler = new Adler32();
        adler.update(dex, 12, dex.length - 12);
        writeInt(dex, 8, (int) adler.getValue());
    }
    
    private static String readMutf8(byte[] data, int off) {
        while ((data[off] & 0x80) != 0) off++;
        off++;
        StringBuilder sb = new StringBuilder();
        while (data[off] != 0) {
            int b = data[off++] & 0xFF;
            if ((b & 0x80) == 0) {
                sb.append((char) b);
            } else if ((b & 0xE0) == 0xC0) {
                sb.append((char) (((b & 0x1F) << 6) | (data[off++] & 0x3F)));
            } else {
                sb.append((char) (((b & 0x0F) << 12) | ((data[off++] & 0x3F) << 6) | (data[off++] & 0x3F)));
            }
        }
        return sb.toString();
    }
    
    private static int readUleb128(byte[] data, int[] pos) {
        int result = 0, shift = 0;
        while (true) {
            int b = data[pos[0]++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }
    
    private static int readInt(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o+1] & 0xFF) << 8) | ((d[o+2] & 0xFF) << 16) | ((d[o+3] & 0xFF) << 24);
    }
    
    private static int readShort(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o+1] & 0xFF) << 8);
    }
    
    private static void writeInt(byte[] d, int o, int v) {
        d[o] = (byte) v;
        d[o+1] = (byte) (v >> 8);
        d[o+2] = (byte) (v >> 16);
        d[o+3] = (byte) (v >> 24);
    }
    
    private static byte[] readFile(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) { fis.read(data); }
        return data;
    }
    
    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
    }
}

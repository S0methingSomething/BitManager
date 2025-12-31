package com.bitmanager.patcher;

import java.io.*;
import java.security.MessageDigest;
import java.util.zip.Adler32;

/**
 * Applies patches to DEX files using pre-computed offsets.
 */
public class DexPatcher {
    
    public static void apply(File dexFile, Patch patch) throws Exception {
        byte[] dex = readFile(dexFile);
        
        // Use pre-computed offset from patches JSON
        int offset = Integer.parseInt(patch.offset.replace("0x", ""), 16);
        byte[] bytes = hexToBytes(patch.bytes);
        
        System.arraycopy(bytes, 0, dex, offset, bytes.length);
        
        updateChecksums(dex);
        writeFile(dexFile, dex);
    }
    
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
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

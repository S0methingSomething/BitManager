package com.bitmanager.patch;

import android.content.Context;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

public class ApkSignerUtil {
    public static void sign(Context ctx, File unsignedApk, File signedApk, Patcher.PatchCallback callback) throws Exception {
        callback.onLog("Loading keystore...");
        
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = ctx.getAssets().open("debug.p12")) {
            ks.load(is, "android".toCharArray());
        }
        
        PrivateKey privateKey = (PrivateKey) ks.getKey("androiddebugkey", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("androiddebugkey");
        
        List<X509Certificate> certs = Collections.singletonList(cert);
        com.android.apksig.ApkSigner.SignerConfig signerConfig = 
            new com.android.apksig.ApkSigner.SignerConfig.Builder("signer", privateKey, certs).build();
        
        callback.onLog("Signing with v1+v2...");
        new com.android.apksig.ApkSigner.Builder(Collections.singletonList(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()
            .sign();
    }
}

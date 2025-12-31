package com.bitmanager.patch;

import android.content.Context;
import com.android.apksig.ApkSigner;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

public class ApkSigner {
    public static void sign(File unsignedApk, File signedApk, Patcher.PatchCallback callback) throws Exception {
        callback.onLog("Loading keystore...");
        
        // Load keystore from assets or generate one
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream ksStream = ApkSigner.class.getResourceAsStream("/assets/debug.p12");
        
        if (ksStream == null) {
            // Generate a new keystore
            callback.onLog("Generating signing key...");
            ks = generateKeyStore();
        } else {
            ks.load(ksStream, "android".toCharArray());
            ksStream.close();
        }
        
        PrivateKey privateKey = (PrivateKey) ks.getKey("androiddebugkey", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("androiddebugkey");
        
        List<X509Certificate> certs = Collections.singletonList(cert);
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
            "signer", privateKey, certs).build();
        
        callback.onLog("Signing with v1+v2...");
        new ApkSigner.Builder(Collections.singletonList(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()
            .sign();
    }
    
    private static KeyStore generateKeyStore() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        
        // Create self-signed certificate
        long now = System.currentTimeMillis();
        java.util.Date startDate = new java.util.Date(now);
        java.util.Date endDate = new java.util.Date(now + 365L * 24 * 60 * 60 * 1000 * 30); // 30 years
        
        // Use Bouncy Castle or simple approach
        sun.security.x509.X500Name owner = new sun.security.x509.X500Name("CN=Android Debug,O=Android,C=US");
        sun.security.x509.X509CertInfo info = new sun.security.x509.X509CertInfo();
        info.set(sun.security.x509.X509CertInfo.VALIDITY, 
            new sun.security.x509.CertificateValidity(startDate, endDate));
        info.set(sun.security.x509.X509CertInfo.SERIAL_NUMBER, 
            new sun.security.x509.CertificateSerialNumber(new java.math.BigInteger(64, new java.security.SecureRandom())));
        info.set(sun.security.x509.X509CertInfo.SUBJECT, owner);
        info.set(sun.security.x509.X509CertInfo.ISSUER, owner);
        info.set(sun.security.x509.X509CertInfo.KEY, new sun.security.x509.CertificateX509Key(kp.getPublic()));
        info.set(sun.security.x509.X509CertInfo.VERSION, new sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3));
        info.set(sun.security.x509.X509CertInfo.ALGORITHM_ID, 
            new sun.security.x509.CertificateAlgorithmId(sun.security.x509.AlgorithmId.get("SHA256withRSA")));
        
        sun.security.x509.X509CertImpl cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(kp.getPrivate(), "SHA256withRSA");
        
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("androiddebugkey", kp.getPrivate(), "android".toCharArray(), 
            new java.security.cert.Certificate[]{cert});
        
        return ks;
    }
}

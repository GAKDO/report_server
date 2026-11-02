package com.gakdo.trace.report.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** CHAIN_SIGNING_KEY 형식(PKCS#8 PEM의 base64 한 줄)의 시험용 Ed25519 키. */
public final class TestKeys {

    private TestKeys() {
    }

    public static String ed25519PemBase64() {
        try {
            byte[] der = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded();
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(der)
                    + "\n-----END PRIVATE KEY-----\n";
            return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

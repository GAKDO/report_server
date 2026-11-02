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
            // PEM 머리글은 나눠 붙인다(gitleaks가 문자열 자체를 키로 보는 오탐 방지).
            String label = "PRIVATE" + " KEY";
            String pem = "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der)
                    + "\n-----END " + label + "-----\n";
            return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.gakdo.trace.report.record.application;

import java.security.SecureRandom;
import java.util.HexFormat;

/** 검증 QR 토큰: 16바이트 난수의 16진수 32자(계약 ^[0-9a-f]{32}$). 추측할 수 없어야 공개 페이지가 렌탈을 훑지 못한다. */
final class VerifyTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private VerifyTokens() {
    }

    static String next() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}

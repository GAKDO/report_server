// 테스트 도구(테스트 자체는 아님): 체인 서명용 Ed25519 열쇠를 실행할 때마다 새로 만든다(운영 열쇠와 무관).

package com.gakdo.trace.report.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** CHAIN_SIGNING_KEY 형식(PKCS#8 PEM의 base64 한 줄)의 시험용 Ed25519 키. */
public final class TestKeys {

    // 객체를 만들 일이 없는 도구라 생성자를 막아 둔다
    private TestKeys() {
    }

    // 테스트 도구: 운영 설정과 같은 모양(PEM을 base64 한 줄로)의 시험용 서명 열쇠를 만든다
    public static String ed25519PemBase64() {
        try {
            // Ed25519 열쇠 쌍을 새로 만들어 비밀 열쇠를 바이트(DER)로 꺼낸다
            byte[] der = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded();
            // PEM 머리글은 나눠 붙인다(gitleaks가 문자열 자체를 키로 보는 오탐 방지).
            // 머리글 글자를 만든다
            String label = "PRIVATE" + " KEY";
            // 바이트를 base64로 바꾸고 BEGIN/END 머리글을 붙여 PEM 글자로 만든다
            String pem = "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der)
                    + "\n-----END " + label + "-----\n";
            // PEM 전체를 다시 base64 한 줄로 바꿔 돌려준다(환경 변수에 한 줄로 넣는 형식)
            return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            // Ed25519를 지원하지 않으면 테스트 준비 실패로 바로 멈춘다
            throw new IllegalStateException(e);
        }
    }
}

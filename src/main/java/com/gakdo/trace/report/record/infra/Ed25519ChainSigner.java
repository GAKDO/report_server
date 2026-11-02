package com.gakdo.trace.report.record.infra;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gakdo.trace.report.record.domain.ChainSigner;

/**
 * 서버 Ed25519 서명(리뷰 9번). 키는 trace-secrets CHAIN_SIGNING_KEY: PKCS#8 PEM을 base64 한 줄로(PEM 그대로도 받음).
 * 서명 대상은 recordHash의 32바이트다.
 */
public final class Ed25519ChainSigner implements ChainSigner {

    private static final Logger log = LoggerFactory.getLogger(Ed25519ChainSigner.class);

    private final Ed25519PrivateKeyParameters privateKey;
    private final Ed25519PublicKeyParameters publicKey;

    Ed25519ChainSigner(Ed25519PrivateKeyParameters privateKey) {
        this.privateKey = privateKey;
        this.publicKey = privateKey.generatePublicKey();
    }

    /**
     * @param configured CHAIN_SIGNING_KEY 값
     * @param allowEphemeral 비어 있을 때 임시 키를 만들지(개발·시험만)
     */
    public static Ed25519ChainSigner fromConfig(String configured, boolean allowEphemeral) {
        if (configured == null || configured.isBlank()) {
            if (!allowEphemeral) {
                throw new IllegalStateException("CHAIN_SIGNING_KEY가 비어 있습니다. trace-secrets에 Ed25519 키를 넣어 주세요"
                        + " (openssl genpkey -algorithm ed25519 | base64 -w0)");
            }
            log.warn("CHAIN_SIGNING_KEY가 없어 임시 Ed25519 키를 만듭니다. 재시작하면 이전 서명은 검증에 실패합니다(개발·시험 전용).");
            return new Ed25519ChainSigner(new Ed25519PrivateKeyParameters(new SecureRandom()));
        }
        return new Ed25519ChainSigner(parse(configured.trim()));
    }

    static Ed25519PrivateKeyParameters parse(String value) {
        try {
            String pem = value.startsWith("-----") ? value
                    : new String(Base64.getMimeDecoder().decode(value), StandardCharsets.US_ASCII);
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            AsymmetricKeyParameter key = PrivateKeyFactory.createKey(Base64.getDecoder().decode(body));
            if (key instanceof Ed25519PrivateKeyParameters ed) {
                return ed;
            }
        } catch (Exception e) {
            throw new IllegalStateException("CHAIN_SIGNING_KEY를 읽을 수 없습니다(PKCS#8 Ed25519 PEM의 base64인지 확인)", e);
        }
        throw new IllegalStateException("CHAIN_SIGNING_KEY가 Ed25519 키가 아닙니다");
    }

    @Override
    public String sign(String recordHashHex) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        byte[] message = HexFormat.of().parseHex(recordHashHex);
        signer.update(message, 0, message.length);
        return Base64.getEncoder().encodeToString(signer.generateSignature());
    }

    @Override
    public boolean verify(String recordHashHex, String signatureBase64) {
        try {
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, publicKey);
            byte[] message = HexFormat.of().parseHex(recordHashHex);
            verifier.update(message, 0, message.length);
            return verifier.verifySignature(Base64.getDecoder().decode(signatureBase64));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 공개 키(raw 32바이트 base64). 기동 로그에 남겨 밖에서도 서명을 확인할 수 있게 한다. */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}

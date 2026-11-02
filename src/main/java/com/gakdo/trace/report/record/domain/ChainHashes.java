package com.gakdo.trace.report.record.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 해시 체인 규칙(trace-chain-1).
 * <ul>
 * <li>payloadHash = SHA-256(정규화 JSON(봉인 내용))</li>
 * <li>recordHash = SHA-256("trace-chain-1\n" + seq + "\n" + rentalId + "\n" + checkpointId + "\n" + checkpointType + "\n"
 * + payloadHash + "\n" + (prevHash 또는 빈 문자열) + "\n" + signedAt(UTC, 마이크로초까지 ISO 8601))</li>
 * <li>prevHash(n) = recordHash(n-1), 첫 기록은 null. 서명은 recordHash 32바이트에 Ed25519.</li>
 * </ul>
 * recordHash는 체인 API(ChainRecord)의 값만으로 누구나 다시 계산할 수 있다.
 */
public final class ChainHashes {

    public static final String RULE = "trace-chain-1";
    private static final DateTimeFormatter SIGNED_AT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private ChainHashes() {
    }

    public static String payloadHash(JsonNode payload) {
        return sha256Hex(CanonicalJson.write(payload).getBytes(StandardCharsets.UTF_8));
    }

    public static String recordHash(int seq, String rentalId, String checkpointId, CheckpointType type,
            String payloadHash, String prevHash, OffsetDateTime signedAt) {
        String message = RULE + "\n" + seq + "\n" + rentalId + "\n" + checkpointId + "\n" + type.name() + "\n"
                + payloadHash + "\n" + (prevHash == null ? "" : prevHash) + "\n" + signedAtText(signedAt);
        return sha256Hex(message.getBytes(StandardCharsets.UTF_8));
    }

    public static String signedAtText(OffsetDateTime signedAt) {
        return signedAt.withOffsetSameInstant(ZoneOffset.UTC).format(SIGNED_AT);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

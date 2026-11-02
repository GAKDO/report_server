package com.gakdo.trace.report.record.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 렌탈 체인을 처음부터 다시 계산한다(FR-INT-01).
 * <ol>
 * <li>seq가 1부터 빠짐없이 이어진다</li>
 * <li>저장한 봉인 내용의 해시 = payloadHash</li>
 * <li>prevHash = 앞 줄 recordHash, recordHash를 다시 계산해 같다</li>
 * <li>Ed25519 서명이 맞다</li>
 * <li>봉인한 사진의 지금 저장소 해시 = 봉인 때 해시(사진 한 장을 바꾸면 여기서 실패)</li>
 * </ol>
 */
public final class ChainCheck {

    private ChainCheck() {
    }

    /** valid가 false면 처음 어긋난 seq와 이유. */
    public record Result(boolean valid, Integer brokenSeq, String reason) {

        static Result ok() {
            return new Result(true, null, null);
        }

        static Result broken(int seq, String reason) {
            return new Result(false, seq, reason);
        }
    }

    /**
     * @param records    seq 순
     * @param objectKeys photoId → 저장소 객체 키(모르면 없음)
     * @param storedHash 객체 키 → 지금 저장소 SHA-256(없으면 빈 값). null이면 저장소는 보지 않는다
     */
    public static Result run(List<ChainRecord> records, ChainSigner signer, ObjectMapper mapper,
            Map<String, String> objectKeys, Function<String, Optional<String>> storedHash) {
        String prev = null;
        for (int i = 0; i < records.size(); i++) {
            ChainRecord r = records.get(i);
            if (r.seq() != i + 1) {
                return Result.broken(r.seq(), "순번이 이어지지 않습니다");
            }
            JsonNode payload = parse(mapper, r.payload());
            if (payload == null || !ChainHashes.payloadHash(payload).equals(r.payloadHash())) {
                return Result.broken(r.seq(), "봉인 내용이 해시와 다릅니다");
            }
            if (!java.util.Objects.equals(prev, r.prevHash())) {
                return Result.broken(r.seq(), "이전 기록 해시와 이어지지 않습니다");
            }
            String recordHash = ChainHashes.recordHash(r.seq(), r.rentalId(), r.checkpointId(), r.checkpointType(),
                    r.payloadHash(), r.prevHash(), r.signedAt());
            if (!recordHash.equals(r.recordHash())) {
                return Result.broken(r.seq(), "기록 해시가 다릅니다");
            }
            if (!signer.verify(recordHash, r.signature())) {
                return Result.broken(r.seq(), "서명이 맞지 않습니다");
            }
            if (storedHash != null) {
                String bad = firstChangedPhoto(payload, objectKeys, storedHash);
                if (bad != null) {
                    return Result.broken(r.seq(), "봉인 뒤 사진이 바뀌었습니다: " + bad);
                }
            }
            prev = recordHash;
        }
        return Result.ok();
    }

    /** 사진 ID 목록(봉인 내용 photos[]). 객체 키를 모르는 사진(업로드 이벤트를 받지 못함)은 저장소 대조를 건너뛴다. */
    private static String firstChangedPhoto(JsonNode payload, Map<String, String> objectKeys,
            Function<String, Optional<String>> storedHash) {
        List<JsonNode> photos = new ArrayList<>();
        payload.path("photos").forEach(photos::add);
        for (JsonNode photo : photos) {
            String key = objectKeys.get(photo.path("photoId").asText());
            if (key == null) {
                continue;
            }
            Optional<String> now = storedHash.apply(key);
            if (now.isEmpty() || !now.get().equals(photo.path("sha256").asText())) {
                return photo.path("photoId").asText();
            }
        }
        return null;
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}

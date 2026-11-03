package com.gakdo.trace.report.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gakdo.trace.report.record.domain.CanonicalJson;
import com.gakdo.trace.report.record.domain.ChainCheck;
import com.gakdo.trace.report.record.domain.ChainHashes;
import com.gakdo.trace.report.record.domain.ChainRecord;
import com.gakdo.trace.report.record.domain.ChainSigner;
import com.gakdo.trace.report.record.domain.CheckpointStatus;
import com.gakdo.trace.report.record.domain.CheckpointType;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.domain.HashRef;
import com.gakdo.trace.report.record.domain.PhotoRef;
import com.gakdo.trace.report.record.domain.SealPayload;
import com.gakdo.trace.report.record.infra.Ed25519ChainSigner;
import com.gakdo.trace.report.support.TestKeys;

class ChainDomainTest {

    private static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    private static final String OUT_CP = "aJ3bYet82WYF8iVlHstT2";
    private static final String RCV_CP = "h40b9AN9xMQhXRHrOyJWB";
    private static final String P1 = "aAXQFO2Vz6ZTFbPctC2dq";
    private static final String P2 = "MR9xaeQrOBS8Jx4XqyKzi";
    private static final String H1 = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b";
    private static final String H2 = "5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef";
    private static final String H3 = "c41d2b6f8a0c3e5d7f9b1a3c5e7d9f0b2a4c6e8d0f1b3a5c7e9d1f2b4a6c9a07";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChainSigner signer = Ed25519ChainSigner.fromConfig(TestKeys.ed25519PemBase64(), false);

    @Test
    @DisplayName("정규화 JSON은 키 순서·공백이 달라도 같은 글자열이라 jsonb로 저장했다 읽어도 해시가 같다")
    void canonicalJsonIgnoresKeyOrderAndWhitespace() throws Exception {
        JsonNode a = mapper.readTree("{\"b\":[1,{\"y\":\"둘\",\"x\":null}],\"a\":true}");
        JsonNode b = mapper.readTree("{ \"a\" : true , \"b\" : [ 1 , { \"x\" : null , \"y\" : \"둘\" } ] }");
        assertThat(CanonicalJson.write(a)).isEqualTo("{\"a\":true,\"b\":[1,{\"x\":null,\"y\":\"둘\"}]}");
        assertThat(ChainHashes.payloadHash(a)).isEqualTo(ChainHashes.payloadHash(b));
        JsonNode fraction = mapper.readTree("{\"f\":1.5}");
        assertThatThrownBy(() -> CanonicalJson.write(fraction))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("봉인 내용은 사진 순서가 달라도 같은 해시이고, 사진 해시 하나가 바뀌면 다른 해시다")
    void payloadHashDependsOnPhotoHashesNotOrder() {
        String one = ChainHashes.payloadHash(payload(List.of(new HashRef(P1, H1), new HashRef(P2, H2))));
        String swapped = ChainHashes.payloadHash(payload(List.of(new HashRef(P2, H2), new HashRef(P1, H1))));
        String changed = ChainHashes.payloadHash(payload(List.of(new HashRef(P1, H3), new HashRef(P2, H2))));
        assertThat(one).isEqualTo(swapped).isNotEqualTo(changed);
    }

    @Test
    @DisplayName("사진 한 장을 바꾸면 검증이 실패한다(저장소 해시가 봉인 때와 다름)")
    void changedPhotoBreaksVerification() {
        List<ChainRecord> chain = twoRecordChain();
        Map<String, String> keys = Map.of(P1, "captures/s/TOP-L1-CAM1.jpg", P2, "captures/s/BOT-L1-CAM1.jpg");
        Map<String, String> store = new HashMap<>(Map.of(keys.get(P1), H1, keys.get(P2), H2));

        assertThat(ChainCheck.run(chain, signer, mapper, keys, k -> Optional.ofNullable(store.get(k))).valid()).isTrue();

        store.put(keys.get(P2), H3); // 반납 사진 한 장을 다른 파일로 바꿈
        ChainCheck.Result result = ChainCheck.run(chain, signer, mapper, keys, k -> Optional.ofNullable(store.get(k)));
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenSeq()).isEqualTo(2);

        store.remove(keys.get(P2)); // 지워도 실패
        assertThat(ChainCheck.run(chain, signer, mapper, keys, k -> Optional.ofNullable(store.get(k))).valid()).isFalse();
    }

    @Test
    @DisplayName("봉인 내용·이전 해시·서명 중 하나라도 고치면 그 seq에서 검증이 실패한다")
    void tamperedRecordsAreDetected() {
        List<ChainRecord> chain = twoRecordChain();
        assertThat(ChainCheck.run(chain, signer, mapper, Map.of(), null).valid()).isTrue();

        ChainRecord second = chain.get(1);
        ChainRecord payloadEdited = copy(second, second.payload().replace("\"grade\":\"B\"", "\"grade\":\"A\""),
                second.prevHash(), second.signature());
        assertThat(ChainCheck.run(List.of(chain.get(0), payloadEdited), signer, mapper, Map.of(), null).brokenSeq())
                .isEqualTo(2);

        ChainRecord unlinked = copy(second, second.payload(), H3, second.signature());
        assertThat(ChainCheck.run(List.of(chain.get(0), unlinked), signer, mapper, Map.of(), null).reason())
                .contains("이전 기록");

        ChainSigner other = Ed25519ChainSigner.fromConfig(null, true);
        assertThat(ChainCheck.run(chain, other, mapper, Map.of(), null).reason()).contains("서명");

        assertThat(ChainCheck.run(List.of(second), signer, mapper, Map.of(), null).reason()).contains("순번");
    }

    @Test
    @DisplayName("서명 키는 PKCS#8 PEM의 base64와 PEM 그대로 둘 다 받고, 비어 있으면 임시 키를 허락할 때만 뜬다")
    void signingKeyFormats() {
        String b64 = TestKeys.ed25519PemBase64();
        String pem = new String(java.util.Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.US_ASCII);
        Ed25519ChainSigner fromB64 = Ed25519ChainSigner.fromConfig(b64, false);
        Ed25519ChainSigner fromPem = Ed25519ChainSigner.fromConfig(pem, false);
        assertThat(fromB64.publicKeyBase64()).isEqualTo(fromPem.publicKeyBase64());
        String sig = fromB64.sign(H1);
        assertThat(fromPem.verify(H1, sig)).isTrue();
        assertThat(fromPem.verify(H2, sig)).isFalse();
        assertThat(fromPem.verify(H1, "not-base64!")).isFalse();
        assertThatThrownBy(() -> Ed25519ChainSigner.fromConfig("", false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAIN_SIGNING_KEY");
        assertThatThrownBy(() -> Ed25519ChainSigner.fromConfig("bm90LWEta2V5", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("시점 상태는 앞으로만 가고, 봉인된 시점은 늦게 온 업로드·등급으로 바뀌지 않는다")
    void checkpointViewOnlyMovesForward() {
        CheckpointView view = new CheckpointView(RENTAL, OUT_CP);
        view.recordGrade("Z4U03wInx137yk13jDsmn", Grade.A, "grade-rule-1.0", List.of(), List.of(), List.of(), now());
        view.recordUpload("Z4U03wInx137yk13jDsmn", now(), List.of(photo(P1, H1)));
        assertThat(view.status()).isEqualTo(CheckpointStatus.GRADED);
        assertThat(view.assignType(CheckpointType.OUT)).isTrue();
        assertThat(view.assignType(CheckpointType.RCV)).isFalse();
        view.markSealed(now());
        view.recordGrade("Z4U03wInx137yk13jDsmn", Grade.D, "x", List.of(), List.of(), List.of(), now());
        assertThat(view.grade()).isEqualTo(Grade.A);
        assertThat(view.status()).isEqualTo(CheckpointStatus.SEALED);
    }

    // ── 도우미 ──

    private ObjectNode payload(List<HashRef> photos) {
        CheckpointView view = new CheckpointView(RENTAL, RCV_CP);
        view.recordGrade("Z4U03wInx137yk13jDsmn", Grade.B, "grade-rule-1.0", List.of("fBSvifsp4FWaM07ql1SET"),
                List.of("SCR1", "KB"), photos, now());
        return SealPayload.of(view, CheckpointType.RCV, Grade.B, null);
    }

    private List<ChainRecord> twoRecordChain() {
        List<ChainRecord> records = new ArrayList<>();
        records.add(chainRecord(1, OUT_CP, CheckpointType.OUT, Grade.A, List.of(new HashRef(P1, H1)), null));
        records.add(chainRecord(2, RCV_CP, CheckpointType.RCV, Grade.B, List.of(new HashRef(P2, H2)),
                records.get(0).recordHash()));
        return records;
    }

    private ChainRecord chainRecord(int seq, String cp, CheckpointType type, Grade grade, List<HashRef> photos, String prev) {
        CheckpointView view = new CheckpointView(RENTAL, cp);
        view.recordGrade("Z4U03wInx137yk13jDsmn", grade, "grade-rule-1.0", List.of(), List.of(), photos, now());
        ObjectNode payload = SealPayload.of(view, type, grade, null);
        String payloadHash = ChainHashes.payloadHash(payload);
        OffsetDateTime at = now();
        String recordHash = ChainHashes.recordHash(seq, RENTAL, cp, type, payloadHash, prev, at);
        return new ChainRecord(new ChainRecord.Position(RENTAL, cp, type, seq), grade,
                new ChainRecord.Seal(CanonicalJson.write(payload), payloadHash, prev, recordHash,
                        signer.sign(recordHash), at),
                false);
    }

    private static ChainRecord copy(ChainRecord r, String payload, String prev, String signature) {
        return new ChainRecord(new ChainRecord.Position(r.rentalId(), r.checkpointId(), r.checkpointType(), r.seq()),
                r.grade(), new ChainRecord.Seal(payload, r.payloadHash(), prev, r.recordHash(), signature, r.signedAt()),
                r.manual());
    }

    private static PhotoRef photo(String id, String sha) {
        return new PhotoRef(id, "TOP", "L1", "CAM1", "captures/s/" + id + ".jpg", sha);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.of(2026, 11, 12, 0, 0, 0, 123_456_000, ZoneOffset.UTC);
    }
}

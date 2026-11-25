// 이 파일이 확인하는 것: 다른 서버들이 보낸 이벤트(메시지 큐)를 report-server가 읽어 들이는 부분.
// 이벤트 종류: 시점 시작, 진단 기록됨, 사진 업로드됨, 검사 완료, 등급 매김, 시험 시점 지움.
// 잘못된 모양의 이벤트는 다시 시도해도 소용없으니 "다시 넣지 않고 거절"해야 큐가 막히지 않는다.

package com.gakdo.trace.report.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.report.common.infra.PublicIds;
import com.gakdo.trace.report.record.application.RecordIngestService;
import com.gakdo.trace.report.record.domain.Pin;
import com.gakdo.trace.report.record.infra.RecordEventListener;

class RecordEventListenerTest {

    private final RecordIngestService ingest = mock(RecordIngestService.class);
    private final RecordEventListener listener = new RecordEventListener(ingest, new ObjectMapper());

    private static String envelope(String version) {
        return "{\"schemaVersion\":\"" + version + "\",\"eventId\":\"" + PublicIds.next()
                + "\",\"occurredAt\":\"2026-11-12T09:00:00+09:00\",\"rentalId\":\"" + PublicIds.next()
                + "\",\"checkpointId\":\"" + PublicIds.next() + "\"";
    }

    // 도우미: 출고 시점 "등급 매김(A)" 이벤트 본문을 만든다. extra로 칸을 더 붙일 수 있다.
    private static String graded(String version, String extra) {
        return envelope(version) + ",\"captureSetId\":\"" + PublicIds.next() + "\",\"checkpointType\":\"OUT\""
                + ",\"grade\":\"A\",\"ruleVersion\":\"grade-rule-1.0\",\"newDefectIds\":[],\"unjudgeableFaces\":[]"
                + ",\"photos\":[]" + extra + "}";
    }

    // [상황] 새 형식(1.1) 등급 이벤트는 흠집 핀(위치 좌표) 1개를 담고, 옛 형식(1.0)은 핀 칸이 없다.
    // [기대] 1.1의 핀은 그대로 읽고, 1.0은 핀 없음으로 받아 둘 다 처리한다.
    @Test
    @DisplayName("1.1 등급 이벤트의 핀은 그대로 읽고, 1.0 이벤트(핀 없음)는 빈 핀으로 받는다")
    void gradedPinsAreOptional() {
        when(ingest.graded(any(), any())).thenReturn(true);
        String defect = PublicIds.next();
        listener.handle("trace.checkpoint.graded", graded("1.1", ",\"pins\":[{\"defectId\":\"" + defect
                + "\",\"face\":\"TOP\",\"x\":10.5,\"y\":0,\"isNew\":true}]"));
        listener.handle("trace.checkpoint.graded", graded("1.0", ""));
        ArgumentCaptor<RecordIngestService.Graded> captor = ArgumentCaptor.forClass(RecordIngestService.Graded.class);
        verify(ingest, times(2)).graded(any(), captor.capture());
        assertThat(captor.getAllValues().get(0).pins()).containsExactly(new Pin(defect, "TOP", 10.5, 0, true));
        assertThat(captor.getAllValues().get(1).pins()).isEmpty();
    }

    // [상황] 핀 좌표가 음수이거나 "새 흠집 여부"가 참/거짓이 아닌 "yes"다.
    // [기대] 다시 넣지 않고 거절한다.
    @Test
    @DisplayName("좌표가 음수이거나 isNew가 참·거짓이 아닌 핀은 다시 시도하지 않고 거절한다")
    void badPinIsRejected() {
        String defect = PublicIds.next();
        String negative = graded("1.1", ",\"pins\":[{\"defectId\":\""
                + defect + "\",\"face\":\"TOP\",\"x\":-1,\"y\":0,\"isNew\":true}]");
        String notBoolean = graded("1.1", ",\"pins\":[{\"defectId\":\""
                + defect + "\",\"face\":\"TOP\",\"x\":1,\"y\":0,\"isNew\":\"yes\"}]");
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", negative))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", notBoolean))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 핀 칸이 목록이 아니거나, 시점 시작 이벤트의 기종 이름이 128자를 넘는다.
    // [기대] 둘 다 거절한다.
    @Test
    @DisplayName("pins가 배열이 아니거나 기종 이름이 128자를 넘으면 거절한다")
    void malformedOptionalFieldsAreRejected() {
        String pinsObject = graded("1.1", ",\"pins\":{}");
        String longModelName = envelope("1.1")
                + ",\"checkpointType\":\"OUT\",\"deviceId\":\"" + PublicIds.next() + "\",\"serial\":\"S1\""
                + ",\"modelCode\":\"MBA13-M2\",\"modelName\":\"" + "a".repeat(129) + "\"}";
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", pinsObject))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.started", longModelName))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 진단 이벤트에 배터리 최대 용량이 89.02%로 있거나, null이거나, 옛 형식이라 칸이 없거나, 250%다.
    // [기대] 있으면 숫자, 없거나 null이면 비움으로 받고, 0~100을 벗어난 250%는 거절한다.
    @Test
    @DisplayName("진단 이벤트의 배터리 최대 용량은 있으면 숫자로, 없거나 null이면 null로 받고, 범위 밖이면 거절한다")
    void maxCapacityPctIsOptional() {
        when(ingest.diagnostics(any(), any())).thenReturn(true);
        String hash = "0".repeat(32) + "1".repeat(32);
        String base = ",\"runId\":\"" + PublicIds.next() + "\",\"sha256\":\"" + hash + "\",\"baseRunId\":null,\"flags\":[]";
        listener.handle("trace.diagnostics.recorded", envelope("1.1") + base + ",\"maxCapacityPct\":89.02}");
        listener.handle("trace.diagnostics.recorded", envelope("1.1") + base + ",\"maxCapacityPct\":null}");
        listener.handle("trace.diagnostics.recorded", envelope("1.0") + base + "}");
        ArgumentCaptor<RecordIngestService.Diagnostics> captor =
                ArgumentCaptor.forClass(RecordIngestService.Diagnostics.class);
        verify(ingest, times(3)).diagnostics(any(), captor.capture());
        assertThat(captor.getAllValues()).extracting(RecordIngestService.Diagnostics::maxCapacityPct)
                .containsExactly(89.02, null, null);
        String outOfRange = envelope("1.1") + base + ",\"maxCapacityPct\":250}";
        assertThatThrownBy(() -> listener.handle("trace.diagnostics.recorded", outOfRange))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 출고 시점 시작 이벤트에 기기 번호·일련번호·기종 코드·기종 이름이 담겨 온다.
    // [기대] 네 값을 그대로 읽어 기록에 넘긴다.
    @Test
    @DisplayName("시점 시작 이벤트(1.1)에서 기기·시리얼·기종 코드·기종 이름을 읽는다")
    void startedCarriesDevice() {
        when(ingest.started(any(), any())).thenReturn(true);
        String device = PublicIds.next();
        boolean done = listener.handle("trace.checkpoint.started", envelope("1.1") + ",\"checkpointType\":\"OUT\""
                + ",\"deviceId\":\"" + device + "\",\"serial\":\"TEST-SERIAL-1\",\"modelCode\":\"MBA13-M2\""
                + ",\"modelName\":\"MacBook Air (M2, 2022)\"}");
        assertThat(done).isTrue();
        verify(ingest).started(any(), eq(
                new RecordIngestService.Started(device, "TEST-SERIAL-1", "MBA13-M2", "MacBook Air (M2, 2022)")));
    }

    // [상황] 검사 워커의 "검사 완료" 이벤트(흠집 목록 등 큰 본문 포함)가 온다.
    // [기대] 진행률 표시에 필요한 렌탈·시점·시각만 읽고 결과 본문은 보지 않는다.
    @Test
    @DisplayName("검사 완료 이벤트는 진행률용으로 봉투(렌탈·시점·시각)만 읽고, 결과 본문은 보지 않는다")
    void inspectCompletedReadsEnvelopeOnly() {
        when(ingest.inspected(any())).thenReturn(true);
        boolean done = listener.handle("trace.inspect.completed", envelope("1.0") + ",\"captureSetId\":\""
                + PublicIds.next() + "\",\"modelVersion\":\"yolo11-seg-v1\",\"alignments\":[],\"defects\":[]"
                + ",\"textureKeys\":{}}");
        assertThat(done).isTrue();
        verify(ingest).inspected(any());
    }

    // [상황] "시험 시점 지움" 이벤트가 일련번호 TEST-0002로 한 번, 실제 기기 일련번호로 한 번 온다.
    // [기대] TEST- 일 때만 지우고, 실제 기기 일련번호면 지우지 않고 거절한다(실수로 운영 기록 삭제 방지).
    @Test
    @DisplayName("시험 시점 지움 이벤트(시리얼 TEST-)는 렌탈 기록을 지우고, 실제 시리얼이면 지우지 않고 거절한다")
    void purgeOnlyTestData() {
        when(ingest.purged(any())).thenReturn(true);
        String base = envelope("1.0") + ",\"checkpointType\":\"OUT\",\"deviceId\":\"" + PublicIds.next() + "\"";
        assertThat(listener.handle("trace.checkpoint.purged", base + ",\"serial\":\"TEST-0002\"}")).isTrue();
        verify(ingest, times(1)).purged(any());
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.purged", base + ",\"serial\":\"C02REAL0001\"}"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(ingest, times(1)).purged(any());
    }
}

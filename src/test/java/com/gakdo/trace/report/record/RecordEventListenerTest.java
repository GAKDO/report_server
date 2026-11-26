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

    // (도구) mock(클래스) = 진짜 일은 안 하고 정해 준 대로만 답하며, 불린 기록을 남기는 가짜(대역) 객체
    // 기록 처리 서비스를 가짜로 만든다(리스너가 무엇을 넘기는지만 보려고)
    private final RecordIngestService ingest = mock(RecordIngestService.class);
    // 가짜 서비스와 진짜 JSON 변환기로 시험할 리스너를 만든다
    private final RecordEventListener listener = new RecordEventListener(ingest, new ObjectMapper());

    // 테스트 도구: 모든 이벤트에 공통인 봉투(버전·이벤트 ID·시각·렌탈·시점) JSON 앞부분을 만든다
    private static String envelope(String version) {
        // ID는 매번 새 NanoID로 채워 이어 붙인다(닫는 괄호는 부르는 쪽이 붙임)
        return "{\"schemaVersion\":\"" + version + "\",\"eventId\":\"" + PublicIds.next()
                + "\",\"occurredAt\":\"2026-11-12T09:00:00+09:00\",\"rentalId\":\"" + PublicIds.next()
                + "\",\"checkpointId\":\"" + PublicIds.next() + "\"";
    }

    // 도우미: 출고 시점 "등급 매김(A)" 이벤트 본문을 만든다. extra로 칸을 더 붙일 수 있다.
    private static String graded(String version, String extra) {
        // 봉투 뒤에 촬영 세트·출고·등급 A·규칙 버전·빈 목록들과 extra를 붙이고 괄호를 닫는다
        return envelope(version) + ",\"captureSetId\":\"" + PublicIds.next() + "\",\"checkpointType\":\"OUT\""
                + ",\"grade\":\"A\",\"ruleVersion\":\"grade-rule-1.0\",\"newDefectIds\":[],\"unjudgeableFaces\":[]"
                + ",\"photos\":[]" + extra + "}";
    }

    // [상황] 새 형식(1.1) 등급 이벤트는 흠집 핀(위치 좌표) 1개를 담고, 옛 형식(1.0)은 핀 칸이 없다.
    // [기대] 1.1의 핀은 그대로 읽고, 1.0은 핀 없음으로 받아 둘 다 처리한다.
    @Test
    @DisplayName("1.1 등급 이벤트의 핀은 그대로 읽고, 1.0 이벤트(핀 없음)는 빈 핀으로 받는다")
    void gradedPinsAreOptional() {
        // (도구) when(...).thenReturn(값) = 그 메서드가 불리면 이 값을 돌려주라고 가짜에게 정해 둔다. any()는 "어떤 값이든"
        // 준비: 등급 처리를 부르면 "처리함"이라고 답하게 한다
        when(ingest.graded(any(), any())).thenReturn(true);
        // 흠집 ID 하나를 만든다
        String defect = PublicIds.next();
        // 실행: 핀 1개가 든 1.1 등급 이벤트를 넣는다
        listener.handle("trace.checkpoint.graded", graded("1.1", ",\"pins\":[{\"defectId\":\"" + defect
                + "\",\"face\":\"TOP\",\"x\":10.5,\"y\":0,\"isNew\":true}]"));
        // 핀 칸이 없는 1.0 등급 이벤트를 넣는다
        listener.handle("trace.checkpoint.graded", graded("1.0", ""));
        // (도구) ArgumentCaptor = 가짜 객체에 넘어간 값을 붙잡아 두는 도구. getAllValues()로 붙잡은 값을 모두 꺼낸다
        // 확인: 서비스에 넘어간 등급 내용을 붙잡을 준비를 한다
        ArgumentCaptor<RecordIngestService.Graded> captor = ArgumentCaptor.forClass(RecordIngestService.Graded.class);
        // (도구) verify(가짜, times(2)).메서드(...) = 그 메서드가 정확히 2번 불렸는지 확인한다
        // 등급 처리가 2번 불렸는지 확인하고 넘어간 내용을 붙잡는다
        verify(ingest, times(2)).graded(any(), captor.capture());
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 첫 번째(1.1)는 핀 1개가 그대로 읽혔는지 확인한다
        assertThat(captor.getAllValues().get(0).pins()).containsExactly(new Pin(defect, "TOP", 10.5, 0, true));
        // 두 번째(1.0)는 핀이 빈 목록인지 확인한다
        assertThat(captor.getAllValues().get(1).pins()).isEmpty();
    }

    // [상황] 핀 좌표가 음수이거나 "새 흠집 여부"가 참/거짓이 아닌 "yes"다.
    // [기대] 다시 넣지 않고 거절한다.
    @Test
    @DisplayName("좌표가 음수이거나 isNew가 참·거짓이 아닌 핀은 다시 시도하지 않고 거절한다")
    void badPinIsRejected() {
        // 준비: 흠집 ID 하나를 만든다
        String defect = PublicIds.next();
        // x 좌표가 -1인 핀이 든 이벤트를 만든다
        String negative = graded("1.1", ",\"pins\":[{\"defectId\":\""
                + defect + "\",\"face\":\"TOP\",\"x\":-1,\"y\":0,\"isNew\":true}]");
        // isNew가 "yes"인 핀이 든 이벤트를 만든다
        String notBoolean = graded("1.1", ",\"pins\":[{\"defectId\":\""
                + defect + "\",\"face\":\"TOP\",\"x\":1,\"y\":0,\"isNew\":\"yes\"}]");
        // (도구) assertThatThrownBy(() -> ...) = 괄호 안 코드를 실행했을 때 오류가 나야 한다는 확인
        // 실행+확인: 음수 좌표 이벤트를 넣으면
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", negative))
                // "다시 넣지 않고 거절" 오류가 나는지 확인한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // "yes" 이벤트를 넣으면
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", notBoolean))
                // 역시 거절 오류가 나는지 확인한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 핀 칸이 목록이 아니거나, 시점 시작 이벤트의 기종 이름이 128자를 넘는다.
    // [기대] 둘 다 거절한다.
    @Test
    @DisplayName("pins가 배열이 아니거나 기종 이름이 128자를 넘으면 거절한다")
    void malformedOptionalFieldsAreRejected() {
        // 준비: pins가 목록이 아니라 객체인 이벤트를 만든다
        String pinsObject = graded("1.1", ",\"pins\":{}");
        // 기종 이름이 129자인 시점 시작 이벤트를 만든다
        String longModelName = envelope("1.1")
                + ",\"checkpointType\":\"OUT\",\"deviceId\":\"" + PublicIds.next() + "\",\"serial\":\"S1\""
                + ",\"modelCode\":\"MBA13-M2\",\"modelName\":\"" + "a".repeat(129) + "\"}";
        // 실행+확인: pins가 객체인 이벤트를 넣으면
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", pinsObject))
                // 거절 오류가 나는지 확인한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 기종 이름이 너무 긴 이벤트를 넣으면
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.started", longModelName))
                // 거절 오류가 나는지 확인한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 진단 이벤트에 배터리 최대 용량이 89.02%로 있거나, null이거나, 옛 형식이라 칸이 없거나, 250%다.
    // [기대] 있으면 숫자, 없거나 null이면 비움으로 받고, 0~100을 벗어난 250%는 거절한다.
    @Test
    @DisplayName("진단 이벤트의 배터리 최대 용량은 있으면 숫자로, 없거나 null이면 null로 받고, 범위 밖이면 거절한다")
    void maxCapacityPctIsOptional() {
        // 준비: 진단 처리를 부르면 "처리함"이라고 답하게 한다
        when(ingest.diagnostics(any(), any())).thenReturn(true);
        // 64자 해시 값을 만든다
        String hash = "0".repeat(32) + "1".repeat(32);
        // 진단 이벤트의 공통 칸(실행 ID·해시·비교 기준·플래그)을 만든다
        String base = ",\"runId\":\"" + PublicIds.next() + "\",\"sha256\":\"" + hash + "\",\"baseRunId\":null,\"flags\":[]";
        // 실행: 최대 용량 89.02%가 든 이벤트를 넣는다
        listener.handle("trace.diagnostics.recorded", envelope("1.1") + base + ",\"maxCapacityPct\":89.02}");
        // 최대 용량이 null인 이벤트를 넣는다
        listener.handle("trace.diagnostics.recorded", envelope("1.1") + base + ",\"maxCapacityPct\":null}");
        // 최대 용량 칸이 없는 옛 형식(1.0) 이벤트를 넣는다
        listener.handle("trace.diagnostics.recorded", envelope("1.0") + base + "}");
        // 확인: 서비스에 넘어간 진단 내용을 붙잡을 준비를 한다
        ArgumentCaptor<RecordIngestService.Diagnostics> captor =
                ArgumentCaptor.forClass(RecordIngestService.Diagnostics.class);
        // 진단 처리가 3번 불렸는지 확인하고 내용을 붙잡는다
        verify(ingest, times(3)).diagnostics(any(), captor.capture());
        // (도구) extracting(...) = 각 항목에서 한 칸만 뽑아낸다. containsExactly(...)는 뽑은 값이 정확히 이 순서로 이것뿐인지 본다
        // 세 번의 최대 용량 값을 뽑아
        assertThat(captor.getAllValues()).extracting(RecordIngestService.Diagnostics::maxCapacityPct)
                // 89.02, null, null인지 확인한다
                .containsExactly(89.02, null, null);
        // 최대 용량이 250%인 이벤트를 만든다
        String outOfRange = envelope("1.1") + base + ",\"maxCapacityPct\":250}";
        // 그 이벤트를 넣으면
        assertThatThrownBy(() -> listener.handle("trace.diagnostics.recorded", outOfRange))
                // 거절 오류가 나는지 확인한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 출고 시점 시작 이벤트에 기기 번호·일련번호·기종 코드·기종 이름이 담겨 온다.
    // [기대] 네 값을 그대로 읽어 기록에 넘긴다.
    @Test
    @DisplayName("시점 시작 이벤트(1.1)에서 기기·시리얼·기종 코드·기종 이름을 읽는다")
    void startedCarriesDevice() {
        // 준비: 시점 시작 처리를 부르면 "처리함"이라고 답하게 한다
        when(ingest.started(any(), any())).thenReturn(true);
        // 기기 ID 하나를 만든다
        String device = PublicIds.next();
        // 실행: 기기 ID·일련번호·기종 코드·기종 이름이 든 시점 시작 이벤트를 넣는다
        boolean done = listener.handle("trace.checkpoint.started", envelope("1.1") + ",\"checkpointType\":\"OUT\""
                + ",\"deviceId\":\"" + device + "\",\"serial\":\"TEST-SERIAL-1\",\"modelCode\":\"MBA13-M2\""
                + ",\"modelName\":\"MacBook Air (M2, 2022)\"}");
        // 확인: 처리됐다고 답하는지 확인한다
        assertThat(done).isTrue();
        // (도구) eq(값) = 정확히 이 값으로 불렸어야 한다는 뜻
        // 네 값이 그대로 서비스에 넘어갔는지 확인한다
        verify(ingest).started(any(), eq(
                new RecordIngestService.Started(device, "TEST-SERIAL-1", "MBA13-M2", "MacBook Air (M2, 2022)")));
    }

    // [상황] 검사 워커의 "검사 완료" 이벤트(흠집 목록 등 큰 본문 포함)가 온다.
    // [기대] 진행률 표시에 필요한 렌탈·시점·시각만 읽고 결과 본문은 보지 않는다.
    @Test
    @DisplayName("검사 완료 이벤트는 진행률용으로 봉투(렌탈·시점·시각)만 읽고, 결과 본문은 보지 않는다")
    void inspectCompletedReadsEnvelopeOnly() {
        // 준비: 검사 완료 처리를 부르면 "처리함"이라고 답하게 한다
        when(ingest.inspected(any())).thenReturn(true);
        // 실행: 결과 본문(정합·흠집·텍스처)이 든 검사 완료 이벤트를 넣는다
        boolean done = listener.handle("trace.inspect.completed", envelope("1.0") + ",\"captureSetId\":\""
                + PublicIds.next() + "\",\"modelVersion\":\"yolo11-seg-v1\",\"alignments\":[],\"defects\":[]"
                + ",\"textureKeys\":{}}");
        // 확인: 처리됐다고 답하는지 확인한다
        assertThat(done).isTrue();
        // 검사 완료 처리가 (봉투만 받아) 한 번 불렸는지 확인한다
        verify(ingest).inspected(any());
    }

    // [상황] "시험 시점 지움" 이벤트가 일련번호 TEST-0002로 한 번, 실제 기기 일련번호로 한 번 온다.
    // [기대] TEST- 일 때만 지우고, 실제 기기 일련번호면 지우지 않고 거절한다(실수로 운영 기록 삭제 방지).
    @Test
    @DisplayName("시험 시점 지움 이벤트(시리얼 TEST-)는 렌탈 기록을 지우고, 실제 시리얼이면 지우지 않고 거절한다")
    void purgeOnlyTestData() {
        // 준비: 지움 처리를 부르면 "처리함"이라고 답하게 한다
        when(ingest.purged(any())).thenReturn(true);
        // 지움 이벤트의 공통 앞부분(봉투·출고·기기 ID)을 만든다
        String base = envelope("1.0") + ",\"checkpointType\":\"OUT\",\"deviceId\":\"" + PublicIds.next() + "\"";
        // 실행+확인: 일련번호 TEST-0002 이벤트는 처리됐다고 답하는지 확인한다
        assertThat(listener.handle("trace.checkpoint.purged", base + ",\"serial\":\"TEST-0002\"}")).isTrue();
        // 지움 처리가 1번 불렸는지 확인한다
        verify(ingest, times(1)).purged(any());
        // 실제 기기 일련번호 이벤트를 넣으면
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.purged", base + ",\"serial\":\"C02REAL0001\"}"))
                // 거절 오류가 나는지 확인한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 지움 처리 횟수가 여전히 1번인지 확인한다(실제 기기 기록은 넘기지도 않음)
        verify(ingest, times(1)).purged(any());
    }
}

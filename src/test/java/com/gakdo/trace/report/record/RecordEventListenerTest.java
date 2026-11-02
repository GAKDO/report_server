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

    private static String graded(String version, String extra) {
        return envelope(version) + ",\"captureSetId\":\"" + PublicIds.next() + "\",\"checkpointType\":\"OUT\""
                + ",\"grade\":\"A\",\"ruleVersion\":\"grade-rule-1.0\",\"newDefectIds\":[],\"unjudgeableFaces\":[]"
                + ",\"photos\":[]" + extra + "}";
    }

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

    @Test
    @DisplayName("좌표가 음수이거나 isNew가 참·거짓이 아닌 핀은 다시 시도하지 않고 거절한다")
    void badPinIsRejected() {
        String defect = PublicIds.next();
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", graded("1.1", ",\"pins\":[{\"defectId\":\""
                + defect + "\",\"face\":\"TOP\",\"x\":-1,\"y\":0,\"isNew\":true}]")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", graded("1.1", ",\"pins\":[{\"defectId\":\""
                + defect + "\",\"face\":\"TOP\",\"x\":1,\"y\":0,\"isNew\":\"yes\"}]")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

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
        assertThatThrownBy(() -> listener.handle("trace.diagnostics.recorded",
                envelope("1.1") + base + ",\"maxCapacityPct\":250}"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

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
}

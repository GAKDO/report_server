package com.gakdo.trace.report.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.DefaultMessage;

import com.gakdo.trace.report.progress.application.WorkerProgress;
import com.gakdo.trace.report.progress.domain.ProgressStage;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.progress.infra.WorkerProgressRedisConfigAccess;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;

class WorkerProgressTest {

    private static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    private static final String CP = "IPv6mcQOlTkKj6mOecAQt";
    private static final String SET = "Z4U03wInx137yk13jDsmq";
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-12T04:20:00Z");

    private final CheckpointViewRepository views = mock(CheckpointViewRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final WorkerProgress progress = new WorkerProgress(views, events);

    private CheckpointView uploaded() {
        CheckpointView view = new CheckpointView(RENTAL, CP);
        view.recordUpload(SET, T1, List.of());
        return view;
    }

    @Test
    @DisplayName("업로드 뒤 워커가 사진 한 장을 정합하면 ALIGNED 40%를 한 번 알린다")
    void alignedAfterFirstPhoto() {
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(uploaded()));
        assertThat(progress.onWorker(RENTAL, new WorkerProgress.Message(SET, CP, "ALIGN", 0, 12))).isFalse();
        assertThat(progress.onWorker(RENTAL, new WorkerProgress.Message(SET, CP, "ALIGN", 1, 12))).isTrue();
        ArgumentCaptor<ProgressUpdate> sent = ArgumentCaptor.forClass(ProgressUpdate.class);
        verify(events).publishEvent(sent.capture());
        assertThat(sent.getValue().stage()).isEqualTo(ProgressStage.ALIGNED);
        assertThat(sent.getValue().stage().percent()).isEqualTo(40);
    }

    @Test
    @DisplayName("검사 완료가 먼저 왔거나 다른 렌탈의 시점이면 정합 진행으로 단계를 되돌리지 않는다")
    void neverRegresses() {
        CheckpointView inspected = uploaded();
        inspected.recordInspected(T1.plusMinutes(1));
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(inspected));
        assertThat(progress.onWorker(RENTAL, new WorkerProgress.Message(SET, CP, "DONE", 12, 12))).isFalse();
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(uploaded()));
        assertThat(progress.onWorker("otherRentalId00000000", new WorkerProgress.Message(SET, CP, "ALIGN", 3, 12)))
                .isFalse();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Redis 채널 trace:progress:{rentalId}의 메시지를 읽고, 깨진 메시지는 무시한다")
    void redisChannel() {
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(uploaded()));
        var listener = WorkerProgressRedisConfigAccess.listener(progress, "trace:");
        byte[] channel = ("trace:progress:" + RENTAL).getBytes(StandardCharsets.UTF_8);
        listener.onMessage(new DefaultMessage(channel, "not json".getBytes(StandardCharsets.UTF_8)), null);
        verify(events, never()).publishEvent(any(Object.class));
        String body = "{\"captureSetId\":\"" + SET + "\",\"checkpointId\":\"" + CP
                + "\",\"stage\":\"ALIGN\",\"done\":2,\"total\":12,\"extra\":1}";
        listener.onMessage(new DefaultMessage(channel, body.getBytes(StandardCharsets.UTF_8)), null);
        verify(events).publishEvent(any(ProgressUpdate.class));
    }
}

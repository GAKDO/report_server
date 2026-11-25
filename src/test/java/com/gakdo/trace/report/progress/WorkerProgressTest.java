// 이 파일이 확인하는 것: 집 PC의 검사 워커(inspect-worker)가 보내는 "사진 정합 진행" 소식을 진행률로 바꾸는 부분.
// 워커는 사진을 기준 모양에 맞춰 펴는(정합) 동안 Redis 채널로 "12장 중 몇 장 끝"을 알린다.

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

    // [상황] 업로드 뒤 워커가 "12장 중 0장" 그리고 "12장 중 1장 정합 끝"을 보낸다.
    // [기대] 0장일 때는 알리지 않고, 1장이 끝나면 "정합 40%"를 한 번 알린다.
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

    // [상황] 이미 검사 완료된 시점에 정합 소식이 늦게 오거나, 다른 렌탈 이름으로 소식이 온다.
    // [기대] 단계를 되돌리지 않고 아무 알림도 보내지 않는다.
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

    // [상황] Redis 채널 trace:progress:{렌탈}로 깨진 글자와 정상 메시지(모르는 칸 포함)가 온다.
    // [기대] 깨진 메시지는 무시하고, 정상 메시지는 진행률 알림으로 바꾼다.
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

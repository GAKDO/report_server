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

    // 테스트에 쓰는 렌탈·시점·촬영 세트 번호와 시각
    private static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    private static final String CP = "IPv6mcQOlTkKj6mOecAQt";
    private static final String SET = "Z4U03wInx137yk13jDsmq";
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-12T04:20:00Z");

    // (도구) mock(클래스) = 진짜 일은 안 하고 정해 준 대로만 답하며, 불린 기록을 남기는 가짜(대역) 객체
    // 시점 저장소를 가짜로 만든다(DB 없이 시점 상태를 정하려고)
    private final CheckpointViewRepository views = mock(CheckpointViewRepository.class);
    // 알림을 내보내는 도구도 가짜로 만든다(알림이 나갔는지 보려고)
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    // 가짜들을 넣어 시험할 워커 진행률 처리기를 만든다
    private final WorkerProgress progress = new WorkerProgress(views, events);

    // 테스트 도구: 사진 업로드까지 끝난 시점을 만든다
    private CheckpointView uploaded() {
        // 빈 시점을 만들고
        CheckpointView view = new CheckpointView(RENTAL, CP);
        // 업로드 이벤트를 적용한다
        view.recordUpload(SET, T1, List.of());
        // 업로드 단계인 시점을 돌려준다
        return view;
    }

    // [상황] 업로드 뒤 워커가 "12장 중 0장" 그리고 "12장 중 1장 정합 끝"을 보낸다.
    // [기대] 0장일 때는 알리지 않고, 1장이 끝나면 "정합 40%"를 한 번 알린다.
    @Test
    @DisplayName("업로드 뒤 워커가 사진 한 장을 정합하면 ALIGNED 40%를 한 번 알린다")
    void alignedAfterFirstPhoto() {
        // (도구) when(...).thenReturn(값) = 그 메서드가 불리면 이 값을 돌려주라고 가짜에게 정해 둔다
        // 준비: 시점을 찾으면 업로드 단계인 시점을 돌려주게 한다
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(uploaded()));
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 실행+확인: "12장 중 0장" 소식은 알리지 않는지(false) 확인한다
        assertThat(progress.onWorker(RENTAL, new WorkerProgress.Message(SET, CP, "ALIGN", 0, 12))).isFalse();
        // "12장 중 1장" 소식은 알리는지(true) 확인한다
        assertThat(progress.onWorker(RENTAL, new WorkerProgress.Message(SET, CP, "ALIGN", 1, 12))).isTrue();
        // (도구) ArgumentCaptor = 가짜 객체에 넘어간 값을 붙잡아 두는 도구. 나중에 getValue()로 꺼내 본다
        // 확인: 나간 알림을 붙잡을 준비를 한다
        ArgumentCaptor<ProgressUpdate> sent = ArgumentCaptor.forClass(ProgressUpdate.class);
        // (도구) verify(가짜).메서드(...) = 그 메서드가 (한 번) 불렸는지 확인한다. capture()는 넘어간 값을 붙잡는다
        // 알림이 한 번 나갔는지 확인하고 그 내용을 붙잡는다
        verify(events).publishEvent(sent.capture());
        // 단계가 "정합"인지 확인한다
        assertThat(sent.getValue().stage()).isEqualTo(ProgressStage.ALIGNED);
        // 진행률이 40%인지 확인한다
        assertThat(sent.getValue().stage().percent()).isEqualTo(40);
    }

    // [상황] 이미 검사 완료된 시점에 정합 소식이 늦게 오거나, 다른 렌탈 이름으로 소식이 온다.
    // [기대] 단계를 되돌리지 않고 아무 알림도 보내지 않는다.
    @Test
    @DisplayName("검사 완료가 먼저 왔거나 다른 렌탈의 시점이면 정합 진행으로 단계를 되돌리지 않는다")
    void neverRegresses() {
        // 준비: 업로드된 시점을 만들고
        CheckpointView inspected = uploaded();
        // 1분 뒤 검사 완료까지 적용한다
        inspected.recordInspected(T1.plusMinutes(1));
        // 시점을 찾으면 검사 완료된 시점을 돌려주게 한다
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(inspected));
        // 실행+확인: 늦게 온 정합 소식은 알리지 않는지 확인한다
        assertThat(progress.onWorker(RENTAL, new WorkerProgress.Message(SET, CP, "DONE", 12, 12))).isFalse();
        // 이번엔 시점을 찾으면 업로드 단계 시점을 돌려주게 바꾼다
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(uploaded()));
        // 다른 렌탈 이름으로 온 소식은
        assertThat(progress.onWorker("otherRentalId00000000", new WorkerProgress.Message(SET, CP, "ALIGN", 3, 12)))
                // 알리지 않는지 확인한다
                .isFalse();
        // (도구) never() = 한 번도 불리지 않았어야 한다는 뜻. any(...)는 "어떤 값이든"
        // 알림이 하나도 나가지 않았는지 확인한다
        verify(events, never()).publishEvent(any(Object.class));
    }

    // [상황] Redis 채널 trace:progress:{렌탈}로 깨진 글자와 정상 메시지(모르는 칸 포함)가 온다.
    // [기대] 깨진 메시지는 무시하고, 정상 메시지는 진행률 알림으로 바꾼다.
    @Test
    @DisplayName("Redis 채널 trace:progress:{rentalId}의 메시지를 읽고, 깨진 메시지는 무시한다")
    void redisChannel() {
        // 준비: 시점을 찾으면 업로드 단계 시점을 돌려주게 한다
        when(views.findByCheckpointId(CP)).thenReturn(Optional.of(uploaded()));
        // 운영과 같은 방법으로 Redis 메시지 리스너를 만든다(키 접두사 trace:)
        var listener = WorkerProgressRedisConfigAccess.listener(progress, "trace:");
        // 이 렌탈의 진행 채널 이름을 바이트로 만든다
        byte[] channel = ("trace:progress:" + RENTAL).getBytes(StandardCharsets.UTF_8);
        // 실행: JSON이 아닌 깨진 메시지를 흘려보낸다
        listener.onMessage(new DefaultMessage(channel, "not json".getBytes(StandardCharsets.UTF_8)), null);
        // 확인: 깨진 메시지로는 알림이 나가지 않았는지 확인한다
        verify(events, never()).publishEvent(any(Object.class));
        // 모르는 칸(extra)이 섞인 정상 메시지 본문을 만든다
        String body = "{\"captureSetId\":\"" + SET + "\",\"checkpointId\":\"" + CP
                + "\",\"stage\":\"ALIGN\",\"done\":2,\"total\":12,\"extra\":1}";
        // 정상 메시지를 흘려보낸다
        listener.onMessage(new DefaultMessage(channel, body.getBytes(StandardCharsets.UTF_8)), null);
        // 진행률 알림이 한 번 나갔는지 확인한다(모르는 칸은 무시)
        verify(events).publishEvent(any(ProgressUpdate.class));
    }
}

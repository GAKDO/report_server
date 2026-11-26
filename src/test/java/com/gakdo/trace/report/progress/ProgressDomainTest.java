// 이 파일이 확인하는 것: 시점 하나의 진행 단계 계산 규칙(서버·화면 없이 규칙만).
// 단계: 업로드 20% → 검사 60% → 등급 80% → 봉인 100%.

package com.gakdo.trace.report.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.gakdo.trace.report.progress.application.ProgressNotifier;
import com.gakdo.trace.report.progress.domain.ProgressStage;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;

class ProgressDomainTest {

    // 테스트에 쓰는 렌탈·시점·촬영 세트 번호와 시각 두 개
    private static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    private static final String CP = "IPv6mcQOlTkKj6mOecAQt";
    private static final String SET = "Z4U03wInx137yk13jDsmq";
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-12T04:20:00Z");
    private static final OffsetDateTime T2 = T1.plusMinutes(1);

    // [상황] 아무 일 없는 시점에 업로드 → 검사 완료 → 등급(C) → 봉인 이벤트를 차례로 적용한다.
    // [기대] 처음엔 단계 없음, 이후 차례로 업로드·검사·등급 단계가 되고, 봉인되면 "완료, 등급 C".
    @Test
    @DisplayName("단계는 이벤트 순서대로 UPLOADED 20 → INSPECTED 60 → GRADED 80 → SEALED 100으로 간다")
    void stagesFollowEvents() {
        // 준비: 아무 일도 없는 시점 하나를 만든다
        CheckpointView view = new CheckpointView(RENTAL, CP);
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 확인: 처음에는 진행 정보가 없는지(빈 값) 확인한다
        assertThat(ProgressUpdate.of(view)).isEmpty();
        // 실행: 업로드 이벤트를 적용한다
        view.recordUpload(SET, T1, List.of());
        // 확인: 단계가 "업로드"인지 확인한다
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.UPLOADED);
        // 실행: 검사 완료 이벤트를 적용한다
        view.recordInspected(T2);
        // 확인: 진행 정보의 시각이 검사 완료 시각인지 확인한다
        assertThat(ProgressUpdate.of(view).orElseThrow().at()).isEqualTo(T2);
        // 단계가 "검사"인지 확인한다
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.INSPECTED);
        // 실행: 등급 C 이벤트를 적용한다
        view.recordGrade(SET, Grade.C, "grade-rules-v1", List.of(), List.of(), List.of(), T2);
        // 확인: 단계가 "등급"인지 확인한다
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.GRADED);
        // 실행: 봉인 이벤트를 적용한다
        view.markSealed(T2);
        // 확인: 마지막 진행 정보를 꺼낸다
        ProgressUpdate done = ProgressUpdate.of(view).orElseThrow();
        // 완료로 표시됐는지 확인한다
        assertThat(done.done()).isTrue();
        // 최종 등급이 C인지 확인한다
        assertThat(done.grade()).isEqualTo(Grade.C);
        // 검사 단계의 진행률이 60%인지 확인한다
        assertThat(ProgressStage.INSPECTED.percent()).isEqualTo(60);
    }

    // [상황] 네트워크 사정으로 "검사 완료"가 "업로드"보다 먼저 도착한다.
    // [기대] 검사 단계로 보이고, 늦게 온 업로드 때문에 뒤로 돌아가지 않으며 알림도 보내지 않는다.
    @Test
    @DisplayName("검사 완료가 업로드보다 먼저 와도 INSPECTED로 보이고, 늦게 온 업로드는 단계를 되돌리지 않는다")
    void outOfOrderDoesNotRegress() {
        // (도구) mock(클래스) = 진짜 일은 안 하고 불린 기록만 남기는 가짜(대역) 객체를 만든다
        // 준비: 알림을 내보내는 도구를 가짜로 만든다(알림이 나갔는지 세려고)
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        // 가짜 도구를 넣은 진행률 알리미를 만든다
        ProgressNotifier notifier = new ProgressNotifier(events);
        // 빈 시점 하나를 만든다
        CheckpointView view = new CheckpointView(RENTAL, CP);
        // 검사 완료가 먼저 도착한다
        view.recordInspected(T1);
        // 업로드 없이도 "검사" 단계로 보이는지 먼저 확인한다
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.INSPECTED);
        // 지금 단계를 적어 둔다(바뀌었는지 비교하려고)
        Optional<ProgressStage> before = ProgressUpdate.stageOf(view);
        // 실행: 늦게 온 업로드를 적용한다
        view.recordUpload(SET, T2, List.of());
        // 단계가 바뀌었으면 알리라고 알리미에게 넘긴다
        notifier.changed(view, before);
        // (도구) verify(가짜, never()).메서드(any(...)) = 그 메서드가 어떤 인자로도 한 번도 불리지 않았는지 확인한다
        // 확인: 알림이 나가지 않았는지 확인한다
        verify(events, never()).publishEvent(any(Object.class));
        // 단계가 "업로드"로 되돌아가지 않고 "검사"인지 확인한다
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.INSPECTED);
    }

    // [상황] 단계가 없던 시점에 업로드가 들어온다.
    // [기대] 단계가 바뀌었으니 진행률 알림을 한 번 보낸다.
    @Test
    @DisplayName("단계가 바뀌면 한 번 알린다")
    void notifiesOnChange() {
        // 준비: 알림을 내보내는 도구를 가짜로 만든다
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        // 빈 시점 하나를 만든다
        CheckpointView view = new CheckpointView(RENTAL, CP);
        // 업로드 이벤트를 적용한다
        view.recordUpload(SET, T1, List.of());
        // 실행: "전에는 단계 없음"이라고 알려 알리미를 부른다
        new ProgressNotifier(events).changed(view, Optional.empty());
        // (도구) verify(가짜).메서드(값) = 그 메서드가 이 값으로 불렸는지 확인한다
        // 확인: 지금 진행 정보로 알림이 한 번 나갔는지 확인한다
        verify(events).publishEvent(ProgressUpdate.of(view).orElseThrow());
    }
}

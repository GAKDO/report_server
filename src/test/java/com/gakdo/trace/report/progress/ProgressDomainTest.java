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

    private static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    private static final String CP = "IPv6mcQOlTkKj6mOecAQt";
    private static final String SET = "Z4U03wInx137yk13jDsmq";
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-12T04:20:00Z");
    private static final OffsetDateTime T2 = T1.plusMinutes(1);

    @Test
    @DisplayName("단계는 이벤트 순서대로 UPLOADED 20 → INSPECTED 60 → GRADED 80 → SEALED 100으로 간다")
    void stagesFollowEvents() {
        CheckpointView view = new CheckpointView(RENTAL, CP);
        assertThat(ProgressUpdate.of(view)).isEmpty();
        view.recordUpload(SET, T1, List.of());
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.UPLOADED);
        view.recordInspected(T2);
        assertThat(ProgressUpdate.of(view).orElseThrow().at()).isEqualTo(T2);
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.INSPECTED);
        view.recordGrade(SET, Grade.C, "grade-rules-v1", List.of(), List.of(), List.of(), T2);
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.GRADED);
        view.markSealed(T2);
        ProgressUpdate done = ProgressUpdate.of(view).orElseThrow();
        assertThat(done.done()).isTrue();
        assertThat(done.grade()).isEqualTo(Grade.C);
        assertThat(ProgressStage.INSPECTED.percent()).isEqualTo(60);
    }

    @Test
    @DisplayName("검사 완료가 업로드보다 먼저 와도 INSPECTED로 보이고, 늦게 온 업로드는 단계를 되돌리지 않는다")
    void outOfOrderDoesNotRegress() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ProgressNotifier notifier = new ProgressNotifier(events);
        CheckpointView view = new CheckpointView(RENTAL, CP);
        view.recordInspected(T1);
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.INSPECTED);
        Optional<ProgressStage> before = ProgressUpdate.stageOf(view);
        view.recordUpload(SET, T2, List.of());
        notifier.changed(view, before);
        verify(events, never()).publishEvent(any(Object.class));
        assertThat(ProgressUpdate.stageOf(view)).contains(ProgressStage.INSPECTED);
    }

    @Test
    @DisplayName("단계가 바뀌면 한 번 알린다")
    void notifiesOnChange() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CheckpointView view = new CheckpointView(RENTAL, CP);
        view.recordUpload(SET, T1, List.of());
        new ProgressNotifier(events).changed(view, Optional.empty());
        verify(events).publishEvent(ProgressUpdate.of(view).orElseThrow());
    }
}

package com.gakdo.trace.report.progress.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;

/**
 * 한 시점의 지금 진행 단계. SEALED면 SSE done(grade), 그 밖에는 progress로 보낸다(contracts streamRentalProgress).
 *
 * @param grade SEALED일 때만(등급 없이 봉인됐으면 null)
 */
public record ProgressUpdate(String rentalId, String checkpointId, ProgressStage stage, OffsetDateTime at, Grade grade) {

    public boolean done() {
        return stage == ProgressStage.SEALED;
    }

    /** 읽기 모델로 지금 단계를 정한다. 아무 이벤트도 없는 시점(OPEN)은 비어 있다. 단계는 앞으로만 간다. */
    public static Optional<ProgressUpdate> of(CheckpointView view) {
        return switch (view.status()) {
            case SEALED -> Optional.of(new ProgressUpdate(view.rentalId(), view.checkpointId(), ProgressStage.SEALED,
                    view.sealedAt(), view.grade()));
            case GRADED -> Optional.of(update(view, ProgressStage.GRADED, view.gradedAt()));
            case CAPTURED -> Optional.of(view.inspectedAt() != null
                    ? update(view, ProgressStage.INSPECTED, view.inspectedAt())
                    : update(view, ProgressStage.UPLOADED, view.occurredAt()));
            case OPEN -> view.inspectedAt() != null
                    ? Optional.of(update(view, ProgressStage.INSPECTED, view.inspectedAt()))
                    : Optional.empty();
        };
    }

    public static Optional<ProgressStage> stageOf(CheckpointView view) {
        return of(view).map(ProgressUpdate::stage);
    }

    private static ProgressUpdate update(CheckpointView view, ProgressStage stage, OffsetDateTime at) {
        return new ProgressUpdate(view.rentalId(), view.checkpointId(), stage, at, null);
    }
}

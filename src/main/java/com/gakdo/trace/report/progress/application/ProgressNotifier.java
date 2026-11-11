package com.gakdo.trace.report.progress.application;

import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.gakdo.trace.report.progress.domain.ProgressStage;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.record.domain.CheckpointView;

/**
 * 읽기 모델을 바꾼 쪽이 부른다. 단계가 실제로 바뀌었을 때만 알리고, 화면에는 DB 커밋 뒤에 나간다(ProgressStreams).
 * 늦게 온 이벤트(예: 등급 뒤의 검사 완료)는 단계를 되돌리지 않으므로 알리지 않는다.
 */
@Component
public class ProgressNotifier {

    private final ApplicationEventPublisher events;

    public ProgressNotifier(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void changed(CheckpointView view, Optional<ProgressStage> before) {
        ProgressUpdate.of(view)
                .filter(after -> before.map(stage -> stage != after.stage()).orElse(true))
                .ifPresent(events::publishEvent);
    }
}

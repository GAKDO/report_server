package com.gakdo.trace.report.progress.application;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.progress.domain.ProgressStage;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;

/**
 * inspect-worker의 면별 정합 진행(Redis)을 SSE ALIGNED(40%) 단계로 바꾼다.
 * 이벤트로 정해진 단계가 아직 UPLOADED일 때만 보낸다(검사 완료가 먼저 왔으면 단계를 되돌리지 않음).
 * 정합을 한 장이라도 마쳤을 때 한 번 ALIGNED가 되고, 같은 단계는 연결마다 한 번만 나간다(ProgressStreams).
 */
@Service
public class WorkerProgress {

    private static final Logger log = LoggerFactory.getLogger(WorkerProgress.class);

    /** worker 메시지 {captureSetId, checkpointId, stage: ALIGN|DONE, done, total} */
    public record Message(String captureSetId, String checkpointId, String stage, Integer done, Integer total) {
        boolean aligned() {
            return ("ALIGN".equals(stage) && done != null && done > 0) || "DONE".equals(stage);
        }
    }

    private final CheckpointViewRepository views;
    private final ApplicationEventPublisher events;

    public WorkerProgress(CheckpointViewRepository views, ApplicationEventPublisher events) {
        this.views = views;
        this.events = events;
    }

    /** @return ALIGNED를 알렸으면 true */
    @Transactional(readOnly = true)
    public boolean onWorker(String rentalId, Message message) {
        if (message == null || message.checkpointId() == null || !message.aligned()) {
            return false;
        }
        Optional<ProgressStage> now = views.findByCheckpointId(message.checkpointId())
                .filter(view -> view.rentalId().equals(rentalId))
                .flatMap(ProgressUpdate::stageOf);
        if (now.isEmpty() || now.get() != ProgressStage.UPLOADED) {
            log.debug("정합 진행을 건너뜁니다 checkpoint={} 지금 단계={}", message.checkpointId(), now.orElse(null));
            return false;
        }
        events.publishEvent(new ProgressUpdate(rentalId, message.checkpointId(), ProgressStage.ALIGNED, Times.now(), null));
        return true;
    }
}

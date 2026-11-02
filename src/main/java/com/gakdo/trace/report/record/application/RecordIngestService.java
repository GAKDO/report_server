package com.gakdo.trace.report.record.application;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.record.domain.CheckpointType;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.domain.HashRef;
import com.gakdo.trace.report.record.domain.PhotoRef;
import com.gakdo.trace.report.record.domain.Pin;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;
import com.gakdo.trace.report.record.infra.ProcessedEventRepository;
import com.gakdo.trace.report.record.infra.RentalReportRepository;

/**
 * 이벤트로 시점 읽기 모델을 채운다. 같은 eventId는 한 번만 처리하고(같은 트랜잭션에 기록), 등급·진단이 오면 봉인을 시도한다.
 */
@Service
public class RecordIngestService {

    private static final Logger log = LoggerFactory.getLogger(RecordIngestService.class);

    /** 봉투(모든 이벤트 공통) */
    public record Envelope(String eventId, String routingKey, String rentalId, String checkpointId,
            OffsetDateTime occurredAt) {
    }

    public record Graded(String captureSetId, CheckpointType type, Grade grade, String ruleVersion,
            List<String> newDefectIds, List<String> unjudgeableFaces, List<HashRef> photos, List<Pin> pins) {
    }

    public record Diagnostics(String runId, String sha256, List<String> flagKinds, Double maxCapacityPct) {
    }

    /** checkpoint.started(1.1): 이 렌탈의 기기·기종 */
    public record Started(String deviceId, String serial, String modelCode, String modelName) {
    }

    private final ProcessedEventRepository processed;
    private final CheckpointViewRepository views;
    private final SealService seals;
    private final RentalReportRepository reports;

    public RecordIngestService(ProcessedEventRepository processed, CheckpointViewRepository views, SealService seals,
            RentalReportRepository reports) {
        this.processed = processed;
        this.views = views;
        this.seals = seals;
        this.reports = reports;
    }

    /** 기기·기종만 리포트 줄에 적는다. 시점 종류는 등급 이벤트가 정한다(체인 규칙을 바꾸지 않으려고). */
    @Transactional
    public boolean started(Envelope env, Started started) {
        if (!firstTime(env)) {
            return false;
        }
        reports.findByRentalId(env.rentalId()).orElseThrow()
                .recordDevice(started.deviceId(), started.serial(), started.modelCode(), started.modelName());
        return true;
    }

    /** @return 처리했으면 true, 이미 처리한 eventId면 false */
    @Transactional
    public boolean uploaded(Envelope env, String captureSetId, List<PhotoRef> photos) {
        if (!firstTime(env)) {
            return false;
        }
        view(env).recordUpload(captureSetId, env.occurredAt(), photos);
        return true;
    }

    @Transactional
    public boolean graded(Envelope env, Graded graded) {
        if (!firstTime(env)) {
            return false;
        }
        CheckpointView view = view(env);
        if (!view.assignType(graded.type())) {
            log.error("시점 종류가 다른 등급 이벤트를 건너뜁니다: checkpointId={} 저장={} 이벤트={}", env.checkpointId(),
                    view.checkpointType(), graded.type());
            return true;
        }
        if (view.isSealed()) {
            log.warn("이미 봉인된 시점의 등급 이벤트는 체인을 바꾸지 않습니다: checkpointId={}", env.checkpointId());
            return true;
        }
        view.recordPins(graded.pins());
        view.recordGrade(graded.captureSetId(), graded.grade(), graded.ruleVersion(), graded.newDefectIds(),
                graded.unjudgeableFaces(), graded.photos(), Times.now()); // 진단 대기는 받은 시각부터
        views.flush();
        seals.sealIfReady(env.checkpointId(), Times.now());
        return true;
    }

    @Transactional
    public boolean diagnostics(Envelope env, Diagnostics diagnostics) {
        if (!firstTime(env)) {
            return false;
        }
        CheckpointView view = view(env);
        if (view.isSealed()) {
            log.warn("봉인 뒤에 온 진단은 화면에만 보이고 체인에는 들어가지 않습니다: checkpointId={} runId={}",
                    env.checkpointId(), diagnostics.runId());
        }
        view.recordDiagnostics(diagnostics.runId(), diagnostics.sha256(), diagnostics.flagKinds(),
                diagnostics.maxCapacityPct());
        views.flush();
        seals.sealIfReady(env.checkpointId(), Times.now());
        return true;
    }

    private boolean firstTime(Envelope env) {
        if (processed.markProcessed(env.eventId(), env.routingKey()) == 0) {
            log.info("이미 처리한 이벤트: {} eventId={}", env.routingKey(), env.eventId());
            return false;
        }
        seals.ensureRental(env.rentalId());
        return true;
    }

    private CheckpointView view(Envelope env) {
        CheckpointView view = views.findByCheckpointId(env.checkpointId())
                .orElseGet(() -> views.save(new CheckpointView(env.rentalId(), env.checkpointId())));
        if (!view.rentalId().equals(env.rentalId())) {
            throw new IllegalArgumentException("시점 " + env.checkpointId() + "의 렌탈이 이벤트와 다릅니다");
        }
        return view;
    }
}

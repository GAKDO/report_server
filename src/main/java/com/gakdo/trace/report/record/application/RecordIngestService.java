package com.gakdo.trace.report.record.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.progress.application.ProgressNotifier;
import com.gakdo.trace.report.progress.domain.ProgressStage;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.record.domain.CheckpointType;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.domain.HashRef;
import com.gakdo.trace.report.record.domain.PhotoRef;
import com.gakdo.trace.report.record.domain.Pin;
import com.gakdo.trace.report.record.domain.RentalReport;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;
import com.gakdo.trace.report.record.infra.ProcessedEventRepository;
import com.gakdo.trace.report.record.infra.RentalReportRepository;

/**
 * 이벤트로 시점 읽기 모델을 채운다. 같은 eventId는 한 번만 처리하고(같은 트랜잭션에 기록), 등급·진단이 오면 봉인을 시도한다.
 */
@Service
public class RecordIngestService {

    private static final Logger log = LoggerFactory.getLogger(RecordIngestService.class);
    private static final String TEST_SERIAL_PREFIX = "TEST-";

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
    private final ProgressNotifier progress;
    private final RecordPurgeStore purges;

    public RecordIngestService(ProcessedEventRepository processed, CheckpointViewRepository views, SealService seals,
            RentalReportRepository reports, ProgressNotifier progress, RecordPurgeStore purges) {
        this.processed = processed;
        this.views = views;
        this.seals = seals;
        this.reports = reports;
        this.progress = progress;
        this.purges = purges;
    }

    /**
     * 시험 렌탈 지우기(trace.checkpoint.purged, 11/4 민서 요청): 그 렌탈의 기록 체인·시점 줄·수동 봉인 기록·리포트를 지운다.
     * 시험 데이터만(리스너가 시리얼 TEST-를 확인). 렌탈째 지우므로 시점마다 와도 결과가 같다(멱등).
     */
    @Transactional
    public boolean purged(Envelope env) {
        // 이벤트의 시리얼만 믿지 않는다. 이 렌탈에 저장된 기기 시리얼이 시험(TEST-)이 아니면 지우지 않는다
        // (같은 브로커 계정을 쓰는 다른 구성요소가 실제 고객 렌탈의 봉인 기록을 지우지 못하게)
        String stored = reports.findByRentalId(env.rentalId()).map(RentalReport::serial).orElse(null);
        if (stored != null && !stored.startsWith(TEST_SERIAL_PREFIX)) {
            log.warn("시험 기기가 아닌 렌탈의 지움 이벤트를 무시함 rental={} checkpoint={}", env.rentalId(), env.checkpointId());
            return false;
        }
        int rows = purges.deleteRental(env.rentalId());
        log.info("시험 렌탈 기록 지움 rental={} checkpoint={} 행={}", env.rentalId(), env.checkpointId(), rows);
        return rows > 0;
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
        CheckpointView view = view(env);
        Optional<ProgressStage> before = ProgressUpdate.stageOf(view);
        view.recordUpload(captureSetId, env.occurredAt(), photos);
        progress.changed(view, before);
        return true;
    }

    /** 검사 완료(RP2 진행률). 결과(흠집·정합)는 capture-server가 등급으로 내므로 여기서는 시각만 적는다. */
    @Transactional
    public boolean inspected(Envelope env) {
        if (!firstTime(env)) {
            return false;
        }
        CheckpointView view = view(env);
        Optional<ProgressStage> before = ProgressUpdate.stageOf(view);
        view.recordInspected(env.occurredAt());
        progress.changed(view, before);
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
        Optional<ProgressStage> before = ProgressUpdate.stageOf(view);
        view.recordPins(graded.pins());
        view.recordGrade(graded.captureSetId(), graded.grade(), graded.ruleVersion(), graded.newDefectIds(),
                graded.unjudgeableFaces(), graded.photos(), Times.now()); // 진단 대기는 받은 시각부터
        progress.changed(view, before);
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

    /** 렌탈 줄을 먼저 잠그고 읽는다(봉인·다른 이벤트와 같은 순서). */
    private CheckpointView view(Envelope env) {
        seals.lockRental(env.rentalId());
        CheckpointView view = views.findByCheckpointId(env.checkpointId())
                .orElseGet(() -> views.save(new CheckpointView(env.rentalId(), env.checkpointId())));
        if (!view.rentalId().equals(env.rentalId())) {
            throw new IllegalArgumentException("시점 " + env.checkpointId() + "의 렌탈이 이벤트와 다릅니다");
        }
        return view;
    }
}

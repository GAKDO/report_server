package com.gakdo.trace.report.record.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gakdo.trace.report.common.domain.DomainException;
import com.gakdo.trace.report.common.domain.ErrorCode;
import com.gakdo.trace.report.common.infra.PublicIds;
import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.progress.application.ProgressNotifier;
import com.gakdo.trace.report.progress.domain.ProgressStage;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.record.domain.CanonicalJson;
import com.gakdo.trace.report.record.domain.ChainHashes;
import com.gakdo.trace.report.record.domain.ChainRecord;
import com.gakdo.trace.report.record.domain.ChainSigner;
import com.gakdo.trace.report.record.domain.CheckpointStatus;
import com.gakdo.trace.report.record.domain.CheckpointType;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.domain.ManualSealLog;
import com.gakdo.trace.report.record.domain.SealPayload;
import com.gakdo.trace.report.record.infra.ChainRecordRepository;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;
import com.gakdo.trace.report.record.infra.ManualSealLogRepository;
import com.gakdo.trace.report.record.infra.RentalReportRepository;

/**
 * 시점 봉인(FR-INT-01). 등급이 나오고(진단이 왔거나 기다리는 시간이 지나면) 시점의 사진·진단 해시를 묶어 이전 기록에 잇고
 * Ed25519로 서명한 뒤 trace.record.sealed를 낸다. 렌탈 줄을 잠가 한 렌탈의 체인은 한 번에 하나씩만 잇는다.
 * 체인 순서는 출고 → 수령 → 반납이고, 앞 시점이 봉인되지 않았으면 기다린다(수동 봉인은 409).
 */
@Service
public class SealService {

    private static final Logger log = LoggerFactory.getLogger(SealService.class);

    private final CheckpointViewRepository views;
    private final ChainRecordRepository chain;
    private final RentalReportRepository reports;
    private final ManualSealLogRepository manualLogs;
    private final ChainSigner signer;
    private final SealedEventPublisher publisher;
    private final ProgressNotifier progress;
    private final ChainVerifier verifier;
    private final Duration diagnosticsWait;

    public SealService(CheckpointViewRepository views, ChainRecordRepository chain, RentalReportRepository reports,
            ManualSealLogRepository manualLogs, ChainSigner signer, SealedEventPublisher publisher,
            ChainVerifier verifier, ProgressNotifier progress,
            @Value("${app.chain.diagnostics-wait:PT1M}") Duration diagnosticsWait) {
        this.progress = progress;
        this.views = views;
        this.chain = chain;
        this.reports = reports;
        this.manualLogs = manualLogs;
        this.signer = signer;
        this.publisher = publisher;
        this.verifier = verifier;
        this.diagnosticsWait = diagnosticsWait;
    }

    public Duration diagnosticsWait() {
        return diagnosticsWait;
    }

    /** 자동 봉인. 조건이 안 맞으면 아무것도 하지 않는다(다음 이벤트나 스케줄러가 다시 부른다). */
    @Transactional
    public Optional<ChainRecord> sealIfReady(String checkpointId, OffsetDateTime now) {
        CheckpointView view = views.findByCheckpointId(checkpointId).orElse(null);
        if (view == null || view.status() != CheckpointStatus.GRADED || view.checkpointType() == null) {
            return Optional.empty();
        }
        if (!view.hasDiagnostics() && view.gradedAt().plus(diagnosticsWait).isAfter(now)) {
            return Optional.empty(); // 진단을 조금 더 기다린다
        }
        lockRental(view.rentalId());
        List<ChainRecord> records = chain.findByRentalIdOrderBySeqAsc(view.rentalId());
        if (!previousSealed(records, view.checkpointType())) {
            log.info("앞 시점이 봉인되지 않아 기다립니다: rentalId={} checkpointId={} type={}", view.rentalId(),
                    checkpointId, view.checkpointType());
            return Optional.empty();
        }
        return Optional.of(seal(view, view.checkpointType(), view.grade(), null, records));
    }

    /** 관리자 수동 봉인(시연 우회로, 11/2 결정). 사용 기록을 남긴다. */
    @Transactional
    public ChainRecord sealManually(String rentalId, String checkpointId, CheckpointType type, Grade grade,
            String reason, String adminId) {
        ensureRental(rentalId);
        lockRental(rentalId);
        CheckpointView view = views.findByCheckpointId(checkpointId)
                .orElseGet(() -> views.save(new CheckpointView(rentalId, checkpointId)));
        if (!view.rentalId().equals(rentalId)) {
            throw DomainException.notFound("이 렌탈의 시점");
        }
        if (view.isSealed()) {
            throw new DomainException(ErrorCode.RECORD_ALREADY_SEALED, null);
        }
        if (!view.assignType(type)) {
            throw DomainException.invalid("checkpointType", "이 시점의 종류는 " + view.checkpointType() + "입니다.");
        }
        List<ChainRecord> records = chain.findByRentalIdOrderBySeqAsc(rentalId);
        if (records.stream().anyMatch(r -> r.checkpointType() == type)) {
            throw new DomainException(ErrorCode.RECORD_ALREADY_SEALED, "이 렌탈의 " + type + " 시점은 이미 봉인됐습니다.");
        }
        if (!previousSealed(records, type)) {
            throw new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, "출고 → 수령 → 반납 순서로 봉인합니다.");
        }
        view.overrideGrade(grade);
        ChainRecord record = seal(view, type, grade, new SealPayload.Manual(adminId, reason), records);
        manualLogs.save(new ManualSealLog(rentalId, checkpointId, type, grade, record.seq(), reason, adminId));
        log.warn("수동 봉인: rentalId={} checkpointId={} type={} grade={} by={}", rentalId, checkpointId, type, grade,
                adminId);
        return record;
    }

    /** 렌탈 줄(검증 토큰)을 없으면 만든다. 이벤트를 처음 받을 때와 수동 봉인 때 부른다. */
    public void ensureRental(String rentalId) {
        reports.insertIfAbsent(rentalId, VerifyTokens.next());
    }

    private void lockRental(String rentalId) {
        ensureRental(rentalId);
        reports.lockByRentalId(rentalId).orElseThrow();
    }

    /** 체인에 앞 시점이 순서대로 모두 있어야 이 시점을 이을 수 있다. */
    static boolean previousSealed(List<ChainRecord> records, CheckpointType type) {
        if (records.size() != type.ordinal()) {
            return false;
        }
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).checkpointType() != CheckpointType.values()[i]) {
                return false;
            }
        }
        return true;
    }

    private ChainRecord seal(CheckpointView view, CheckpointType type, Grade grade, SealPayload.Manual manual,
            List<ChainRecord> records) {
        int seq = records.size() + 1;
        String prevHash = records.isEmpty() ? null : records.get(records.size() - 1).recordHash();
        ObjectNode payload = SealPayload.of(view, type, grade, manual);
        String payloadHash = ChainHashes.payloadHash(payload);
        OffsetDateTime signedAt = Times.now();
        String recordHash = ChainHashes.recordHash(seq, view.rentalId(), view.checkpointId(), type, payloadHash,
                prevHash, signedAt);
        ChainRecord record = chain.save(new ChainRecord(view.rentalId(), view.checkpointId(), type, seq, grade,
                CanonicalJson.write(payload), payloadHash, prevHash, recordHash, signer.sign(recordHash), signedAt,
                manual != null));
        Optional<ProgressStage> before = ProgressUpdate.stageOf(view);
        view.markSealed(signedAt);
        progress.changed(view, before);
        String eventId = PublicIds.next();
        publisher.publish(eventId, sealedEvent(eventId, record));
        verifier.invalidate(view.rentalId());
        log.info("봉인: rentalId={} checkpointId={} seq={} type={} grade={} payloadHash={}", view.rentalId(),
                view.checkpointId(), seq, type, grade, payloadHash);
        return record;
    }

    /** contracts events/trace.record.sealed.schema.json 1.0 */
    static Map<String, Object> sealedEvent(String eventId, ChainRecord record) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", "1.0");
        event.put("eventId", eventId);
        event.put("occurredAt", record.signedAt().toString());
        event.put("rentalId", record.rentalId());
        event.put("checkpointId", record.checkpointId());
        event.put("seq", record.seq());
        event.put("checkpointType", record.checkpointType().name());
        event.put("grade", record.grade().name());
        event.put("payloadHash", record.payloadHash());
        event.put("prevHash", record.prevHash());
        event.put("signedAt", record.signedAt().toString());
        return event;
    }
}

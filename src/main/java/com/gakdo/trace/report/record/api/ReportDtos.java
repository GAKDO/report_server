package com.gakdo.trace.report.record.api;

import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.report.record.domain.ChainRecord;
import com.gakdo.trace.report.record.domain.CheckpointType;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.domain.ManualSealLog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** contracts openapi/report-server.yaml 스키마와 같은 모양. 엔티티는 밖으로 내보내지 않는다. */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record PinResponse(String defectId, String face, double x, double y, boolean isNew) {
    }

    public record DiagnosticsSummaryResponse(Double maxCapacityPct, List<String> flags) {
    }

    public record TimelineCheckpointResponse(String checkpointId, String type, String status, OffsetDateTime occurredAt,
            String grade, int newDefectCount, List<PinResponse> pins, DiagnosticsSummaryResponse diagnosticsSummary,
            OffsetDateTime sealedAt) {

        static TimelineCheckpointResponse of(CheckpointView v) {
            DiagnosticsSummaryResponse diagnostics = v.hasDiagnostics()
                    ? new DiagnosticsSummaryResponse(null, v.diagFlags())
                    : null;
            // 핀(흠집 위치)은 이벤트에 아직 없다(계약 1.1 결정 대기). 그 전에는 빈 배열
            return new TimelineCheckpointResponse(v.checkpointId(), v.checkpointType().name(), v.status().name(),
                    v.occurredAt(), v.grade() == null ? null : v.grade().name(), v.newDefectIds().size(), List.of(),
                    diagnostics, v.sealedAt());
        }
    }

    public record RentalTimelineResponse(String rentalId, String modelCode, String chainStatus, String verifyUrl,
            List<TimelineCheckpointResponse> checkpoints) {
    }

    public record ChainRecordResponse(int seq, String checkpointId, String checkpointType, String payloadHash,
            String prevHash, String signature, OffsetDateTime signedAt) {

        static ChainRecordResponse of(ChainRecord r) {
            return new ChainRecordResponse(r.seq(), r.checkpointId(), r.checkpointType().name(), r.payloadHash(),
                    r.prevHash(), r.signature(), r.signedAt());
        }
    }

    public record ChainResponse(String rentalId, boolean valid, List<ChainRecordResponse> records) {
    }

    public record VerifyRecordResponse(int seq, String checkpointType, String grade, String payloadHash,
            OffsetDateTime signedAt) {

        static VerifyRecordResponse of(ChainRecord r) {
            return new VerifyRecordResponse(r.seq(), r.checkpointType().name(), r.grade().name(), r.payloadHash(),
                    r.signedAt());
        }
    }

    /** 개인정보·사진·진단 상세 필드를 두지 않는다(계약 additionalProperties: false). */
    public record VerifyResponse(boolean valid, String modelName, List<VerifyRecordResponse> records,
            OffsetDateTime checkedAt) {
    }

    public record ManualSealRequest(@NotNull CheckpointType checkpointType, @NotNull Grade grade,
            @NotBlank @Size(min = 5, max = 300) String reason) {

        /** 검증 전에 앞뒤 공백을 지워 "   a   " 같은 값이 5자 규칙을 통과하지 못하게 한다. */
        public ManualSealRequest {
            reason = reason == null ? null : reason.strip();
        }
    }

    public record ManualSealItem(String rentalId, String checkpointId, String checkpointType, String grade, int seq,
            String reason, String sealedBy, OffsetDateTime sealedAt) {

        static ManualSealItem of(ManualSealLog l) {
            return new ManualSealItem(l.rentalId(), l.checkpointId(), l.checkpointType().name(), l.grade().name(),
                    l.seq(), l.reason(), l.sealedBy(), l.createdAt());
        }
    }

    public record ManualSealPage(List<ManualSealItem> items, String nextCursor) {
    }
}

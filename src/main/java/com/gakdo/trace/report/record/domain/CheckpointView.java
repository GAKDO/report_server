package com.gakdo.trace.report.record.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gakdo.trace.report.common.infra.Times;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 시점 하나의 읽기 모델(report.checkpoint_view). 이벤트가 오는 순서와 상관없이 같은 결과가 되도록 각 이벤트는 자기 칸만 채운다.
 * 봉인된 뒤에 온 등급·사진은 체인을 바꾸지 않으므로 무시하고, 늦게 온 진단은 화면용으로만 남긴다.
 */
@Entity
@Table(name = "checkpoint_view")
public class CheckpointView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rental_id", nullable = false, length = 21)
    private String rentalId;

    @Column(name = "checkpoint_id", nullable = false, length = 21, unique = true)
    private String checkpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_type", length = 3)
    private CheckpointType checkpointType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CheckpointStatus status = CheckpointStatus.OPEN;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "capture_set_id", length = 21)
    private String captureSetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<PhotoRef> photos = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chain_photos", nullable = false)
    private List<HashRef> chainPhotos = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 1, columnDefinition = "char(1)")
    private Grade grade;

    @Column(name = "rule_version", length = 32)
    private String ruleVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_defect_ids", nullable = false)
    private List<String> newDefectIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unjudgeable_faces", nullable = false)
    private List<String> unjudgeableFaces = new ArrayList<>();

    /** 흠집 핀(화면용, 체인 재료 아님). 1.0 이벤트면 빈 배열 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Pin> pins = new ArrayList<>();

    @Column(name = "graded_at")
    private OffsetDateTime gradedAt;

    @Column(name = "diag_run_id", length = 21)
    private String diagRunId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "diag_sha256", length = 64, columnDefinition = "char(64)")
    private String diagSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diag_flags", nullable = false)
    private List<String> diagFlags = new ArrayList<>();

    @Column(name = "diag_max_capacity_pct")
    private Double diagMaxCapacityPct;

    @Column(name = "inspected_at")
    private OffsetDateTime inspectedAt;

    @Column(name = "sealed_at")
    private OffsetDateTime sealedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CheckpointView() {
    }

    public CheckpointView(String rentalId, String checkpointId) {
        this.rentalId = rentalId;
        this.checkpointId = checkpointId;
    }

    public boolean isSealed() {
        return status == CheckpointStatus.SEALED;
    }

    /** 시점 종류는 한 번 정해지면 바뀌지 않는다. 다른 값이 오면 false. */
    public boolean assignType(CheckpointType type) {
        if (checkpointType == null) {
            checkpointType = type;
            return true;
        }
        return checkpointType == type;
    }

    public void recordUpload(String setId, OffsetDateTime at, List<PhotoRef> uploaded) {
        if (isSealed()) {
            return;
        }
        captureSetId = setId;
        occurredAt = Times.utc(at);
        photos = new ArrayList<>(uploaded);
        status = status.advanceTo(CheckpointStatus.CAPTURED);
    }

    public void recordGrade(String setId, Grade value, String rule, List<String> newDefects, List<String> unjudgeable,
            List<HashRef> chainMaterial, OffsetDateTime at) {
        if (isSealed()) {
            return;
        }
        captureSetId = setId;
        grade = value;
        ruleVersion = rule;
        newDefectIds = new ArrayList<>(newDefects);
        unjudgeableFaces = new ArrayList<>(unjudgeable);
        chainPhotos = new ArrayList<>(chainMaterial);
        gradedAt = Times.utc(at);
        status = status.advanceTo(CheckpointStatus.GRADED);
    }

    /** 검사(정합·흠집 탐지)가 끝난 시각(RP2 진행률). 처음 받은 시각만 둔다. */
    public void recordInspected(OffsetDateTime at) {
        if (isSealed() || inspectedAt != null) {
            return;
        }
        inspectedAt = Times.utc(at);
    }

    /** 핀은 등급과 함께 오지만 체인 재료가 아니라 따로 받는다. 봉인 뒤에는 등급처럼 바꾸지 않는다. */
    public void recordPins(List<Pin> values) {
        if (isSealed()) {
            return;
        }
        pins = new ArrayList<>(values);
    }

    /** 봉인 뒤에 온 진단도 화면에는 보이지만 체인에는 들어가지 않는다(SealService가 봉인 때 읽은 값만 묶는다). */
    public void recordDiagnostics(String runId, String sha256, List<String> flagKinds, Double maxCapacityPct) {
        diagRunId = runId;
        diagMaxCapacityPct = maxCapacityPct;
        diagSha256 = sha256;
        diagFlags = new ArrayList<>(flagKinds);
    }

    /** 수동 봉인: 파이프라인 등급이 없으면 관리자가 적은 등급을 쓴다. */
    public void overrideGrade(Grade value) {
        grade = value;
    }

    public void markSealed(OffsetDateTime at) {
        status = CheckpointStatus.SEALED;
        sealedAt = Times.utc(at);
    }

    public boolean hasDiagnostics() {
        return diagRunId != null;
    }

    @PrePersist
    void onCreate() {
        createdAt = Times.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Times.now();
    }

    public String rentalId() { return rentalId; }
    public String checkpointId() { return checkpointId; }
    public CheckpointType checkpointType() { return checkpointType; }
    public CheckpointStatus status() { return status; }
    public OffsetDateTime occurredAt() { return occurredAt; }
    public String captureSetId() { return captureSetId; }
    public List<PhotoRef> photos() { return List.copyOf(photos); }
    public List<HashRef> chainPhotos() { return List.copyOf(chainPhotos); }
    public Grade grade() { return grade; }
    public String ruleVersion() { return ruleVersion; }
    public List<String> newDefectIds() { return List.copyOf(newDefectIds); }
    public List<String> unjudgeableFaces() { return List.copyOf(unjudgeableFaces); }
    public List<Pin> pins() { return List.copyOf(pins); }
    public Double diagMaxCapacityPct() { return diagMaxCapacityPct; }
    public OffsetDateTime gradedAt() { return gradedAt; }
    public String diagRunId() { return diagRunId; }
    public String diagSha256() { return diagSha256; }
    public List<String> diagFlags() { return List.copyOf(diagFlags); }
    public OffsetDateTime inspectedAt() { return inspectedAt; }
    public OffsetDateTime sealedAt() { return sealedAt; }
}

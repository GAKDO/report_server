package com.gakdo.trace.report.record.domain;

import java.time.OffsetDateTime;

import com.gakdo.trace.report.common.infra.Times;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** 관리자 수동 봉인 사용 기록(report.manual_seal_log, 11/2 결정). */
@Entity
@Table(name = "manual_seal_log")
public class ManualSealLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rental_id", nullable = false, length = 21)
    private String rentalId;

    @Column(name = "checkpoint_id", nullable = false, length = 21)
    private String checkpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_type", nullable = false, length = 3)
    private CheckpointType checkpointType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 1, columnDefinition = "char(1)")
    private Grade grade;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(name = "sealed_by", nullable = false, length = 64)
    private String sealedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ManualSealLog() {
    }

    public ManualSealLog(String rentalId, String checkpointId, CheckpointType checkpointType, Grade grade, int seq,
            String reason, String sealedBy) {
        this.rentalId = rentalId;
        this.checkpointId = checkpointId;
        this.checkpointType = checkpointType;
        this.grade = grade;
        this.seq = seq;
        this.reason = reason;
        this.sealedBy = sealedBy;
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

    public Long id() { return id; }
    public String rentalId() { return rentalId; }
    public String checkpointId() { return checkpointId; }
    public CheckpointType checkpointType() { return checkpointType; }
    public Grade grade() { return grade; }
    public int seq() { return seq; }
    public String reason() { return reason; }
    public String sealedBy() { return sealedBy; }
    public OffsetDateTime createdAt() { return createdAt; }
}

package com.gakdo.trace.report.record.domain;

import java.time.OffsetDateTime;

import com.gakdo.trace.report.common.infra.Times;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    /**
     * 봉인한 시점. 렌탈·시점 종류·등급·순번은 같은 시점의 체인 한 줄(record_chain)에 이미 있어 여기에 다시 두지 않는다(V5).
     * FK: report.record_chain(checkpoint_id)
     */
    @Column(name = "checkpoint_id", nullable = false, length = 21)
    private String checkpointId;

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

    public ManualSealLog(String checkpointId, String reason, String sealedBy) {
        this.checkpointId = checkpointId;
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
    public String checkpointId() { return checkpointId; }
    public String reason() { return reason; }
    public String sealedBy() { return sealedBy; }
    public OffsetDateTime createdAt() { return createdAt; }
}

package com.gakdo.trace.report.record.domain;

import java.time.OffsetDateTime;

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

/** 해시 체인 한 줄(report.record_chain). 한 번 쓰면 고치지 않는다. */
@Entity
@Table(name = "record_chain")
public class ChainRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rental_id", nullable = false, length = 21, updatable = false)
    private String rentalId;

    @Column(name = "checkpoint_id", nullable = false, length = 21, updatable = false)
    private String checkpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_type", nullable = false, length = 3, updatable = false)
    private CheckpointType checkpointType;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 1, updatable = false, columnDefinition = "char(1)")
    private Grade grade;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false, columnDefinition = "char(64)")
    private String payloadHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "prev_hash", length = 64, updatable = false, columnDefinition = "char(64)")
    private String prevHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "record_hash", nullable = false, length = 64, updatable = false, columnDefinition = "char(64)")
    private String recordHash;

    @Column(nullable = false, updatable = false)
    private String signature;

    @Column(name = "signed_at", nullable = false, updatable = false)
    private OffsetDateTime signedAt;

    @Column(nullable = false, updatable = false)
    private boolean manual;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ChainRecord() {
    }

    public ChainRecord(String rentalId, String checkpointId, CheckpointType checkpointType, int seq, Grade grade,
            String payload, String payloadHash, String prevHash, String recordHash, String signature,
            OffsetDateTime signedAt, boolean manual) {
        this.rentalId = rentalId;
        this.checkpointId = checkpointId;
        this.checkpointType = checkpointType;
        this.seq = seq;
        this.grade = grade;
        this.payload = payload;
        this.payloadHash = payloadHash;
        this.prevHash = prevHash;
        this.recordHash = recordHash;
        this.signature = signature;
        this.signedAt = signedAt;
        this.manual = manual;
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
    public int seq() { return seq; }
    public Grade grade() { return grade; }
    public String payload() { return payload; }
    public String payloadHash() { return payloadHash; }
    public String prevHash() { return prevHash; }
    public String recordHash() { return recordHash; }
    public String signature() { return signature; }
    public OffsetDateTime signedAt() { return signedAt; }
    public boolean manual() { return manual; }
}

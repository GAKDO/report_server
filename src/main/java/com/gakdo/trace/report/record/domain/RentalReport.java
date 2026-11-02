package com.gakdo.trace.report.record.domain;

import java.time.OffsetDateTime;

import com.gakdo.trace.report.common.infra.Times;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** 렌탈마다 한 줄(report.report): 검증 QR 토큰. 봉인할 때 이 줄을 잠가 렌탈의 체인을 한 번에 하나씩 잇는다. */
@Entity
@Table(name = "report")
public class RentalReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rental_id", nullable = false, length = 21, unique = true, updatable = false)
    private String rentalId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "verify_token", nullable = false, length = 32, unique = true, updatable = false, columnDefinition = "char(32)")
    private String verifyToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RentalReport() {
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
    public String verifyToken() { return verifyToken; }
}

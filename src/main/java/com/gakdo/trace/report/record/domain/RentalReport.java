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

    @Column(name = "device_id", length = 21)
    private String deviceId;

    @Column(length = 64)
    private String serial;

    @Column(name = "model_code", length = 64)
    private String modelCode;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RentalReport() {
    }

    /** checkpoint.started가 알려 준 기기·기종. 렌탈 중 기기는 바뀌지 않지만 늦게 온 값이 덮어도 같은 값이다. */
    /** 렌탈 하나는 기기 하나라 처음 적힌 시리얼은 바꾸지 않는다(뒤 이벤트가 TEST-로 덮어써 시험 지우기를 통과하지 못하게). */
    public void recordDevice(String device, String serialNo, String code, String name) {
        deviceId = device;
        if (serial == null) {
            serial = serialNo;
        }
        modelCode = code;
        modelName = name;
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
    public String deviceId() { return deviceId; }
    public String serial() { return serial; }
    public String modelCode() { return modelCode; }
    public String modelName() { return modelName; }
    public Long id() { return id; }
    public OffsetDateTime updatedAt() { return updatedAt; }
}

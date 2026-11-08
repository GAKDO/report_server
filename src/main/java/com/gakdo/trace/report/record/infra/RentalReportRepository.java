package com.gakdo.trace.report.record.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.report.record.domain.RentalReport;

import jakarta.persistence.LockModeType;

public interface RentalReportRepository extends JpaRepository<RentalReport, Long> {

    /** 렌탈 줄이 없으면 만든다(동시에 와도 하나만). 검증 토큰은 16바이트 난수의 16진수 32자. */
    @Modifying
    @Query(value = "INSERT INTO report.report (rental_id, verify_token) VALUES (:rentalId, :token)"
            + " ON CONFLICT (rental_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(String rentalId, String token);

    /** 봉인은 렌탈마다 한 번에 하나: 이 줄을 잠그고 체인 끝을 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RentalReport r where r.rentalId = :rentalId")
    Optional<RentalReport> lockByRentalId(String rentalId);

    Optional<RentalReport> findByRentalId(String rentalId);

    Optional<RentalReport> findByVerifyToken(String verifyToken);

    /** 기록 검색(FR-RPT-05): 시리얼·렌탈 ID 일부(대소문자 무시). id 내림차순 키셋 페이지(beforeId 미만). */
    @Query("select r from RentalReport r where r.id < :beforeId and (lower(r.serial) like :pattern"
            + " or lower(r.rentalId) like :pattern) order by r.id desc")
    List<RentalReport> search(String pattern, long beforeId, Pageable page);
}

package com.gakdo.trace.report.record.infra;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.report.record.domain.ManualSealLog;

public interface ManualSealLogRepository extends JpaRepository<ManualSealLog, Long> {

    List<ManualSealLog> findAllByOrderByIdDesc(Pageable page);

    List<ManualSealLog> findByIdLessThanOrderByIdDesc(Long id, Pageable page);
}

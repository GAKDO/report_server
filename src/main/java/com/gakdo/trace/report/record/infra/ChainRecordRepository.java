package com.gakdo.trace.report.record.infra;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.report.record.domain.ChainRecord;

public interface ChainRecordRepository extends JpaRepository<ChainRecord, Long> {

    List<ChainRecord> findByRentalIdOrderBySeqAsc(String rentalId);
}

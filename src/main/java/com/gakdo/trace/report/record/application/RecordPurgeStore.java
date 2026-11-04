package com.gakdo.trace.report.record.application;

/** 렌탈 하나의 기록 행을 지우는 포트(시험 데이터 지우기). 구현은 infra(JDBC). */
public interface RecordPurgeStore {

    /** @return 지운 행 수(이미 없으면 0) */
    int deleteRental(String rentalId);
}

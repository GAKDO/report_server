package com.gakdo.trace.report.record.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.report.record.application.RecordPurgeStore;

/** report 스키마에서 rental_id가 같은 행을 모두 지운다. 테이블 사이 FK가 없어 순서는 읽기 쉬운 쪽(자식 → 부모)으로 둔다. */
@Repository
public class JdbcRecordPurgeStore implements RecordPurgeStore {

    private static final String[] TABLES = {
        "report.record_chain", "report.checkpoint_view", "report.manual_seal_log", "report.report_view", "report.report"
    };

    private final JdbcTemplate jdbc;

    public JdbcRecordPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteRental(String rentalId) {
        int rows = 0;
        for (String table : TABLES) {
            rows += jdbc.update("delete from " + table + " where rental_id = ?", rentalId);
        }
        return rows;
    }
}

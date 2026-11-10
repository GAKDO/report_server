package com.gakdo.trace.report.record.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.report.record.application.RecordPurgeStore;

/** report 스키마에서 rental_id가 같은 행을 모두 지운다. 테이블 사이 FK가 없어 순서는 읽기 쉬운 쪽(자식 → 부모)으로 둔다. */
@Repository
public class JdbcRecordPurgeStore implements RecordPurgeStore {

    /** 표 이름을 문장에 이어 붙이지 않고 완성된 문장으로 둔다(동적 SQL을 만들지 않음) */
    private static final String[] DELETES = {
        "delete from report.record_chain where rental_id = ?",
        "delete from report.checkpoint_view where rental_id = ?",
        "delete from report.manual_seal_log where rental_id = ?",
        "delete from report.report_view where rental_id = ?",
        "delete from report.report where rental_id = ?"
    };

    private final JdbcTemplate jdbc;

    public JdbcRecordPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteRental(String rentalId) {
        int rows = 0;
        for (String delete : DELETES) {
            rows += jdbc.update(delete, rentalId);
        }
        return rows;
    }
}

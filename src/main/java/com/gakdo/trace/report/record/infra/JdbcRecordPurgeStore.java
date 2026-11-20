package com.gakdo.trace.report.record.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.report.record.application.RecordPurgeStore;

/**
 * report 스키마에서 그 렌탈의 행을 모두 지운다. 수동 봉인 기록은 체인 줄을 FK로 가리키므로 체인보다 먼저 지운다.
 * (V1의 report_view는 RP1부터 쓰지 않아 건드리지 않는다. 표는 다음 마이그레이션에서 지운다.)
 */
@Repository
public class JdbcRecordPurgeStore implements RecordPurgeStore {

    /** 표 이름을 문장에 이어 붙이지 않고 완성된 문장으로 둔다(동적 SQL을 만들지 않음) */
    private static final String[] DELETES = {
        "delete from report.manual_seal_log where checkpoint_id in"
                + " (select checkpoint_id from report.record_chain where rental_id = ?)",
        "delete from report.record_chain where rental_id = ?",
        "delete from report.checkpoint_view where rental_id = ?",
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

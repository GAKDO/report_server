package com.gakdo.trace.report.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.report.progress.application.ProgressNotifier;
import com.gakdo.trace.report.record.application.RecordIngestService;
import com.gakdo.trace.report.record.application.RecordIngestService.Envelope;
import com.gakdo.trace.report.record.application.RecordPurgeStore;
import com.gakdo.trace.report.record.application.SealService;
import com.gakdo.trace.report.record.domain.RentalReport;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;
import com.gakdo.trace.report.record.infra.ProcessedEventRepository;
import com.gakdo.trace.report.record.infra.RentalReportRepository;

/** 시험 렌탈 지움(trace.checkpoint.purged)은 저장된 기기 시리얼이 TEST-일 때만 지운다. */
class RecordPurgeGuardTest {

    private static final String RENTAL = "rental_0000000000001";
    private final RentalReportRepository reports = mock(RentalReportRepository.class);
    private final RecordPurgeStore purges = mock(RecordPurgeStore.class);
    private final RecordIngestService ingest = new RecordIngestService(mock(ProcessedEventRepository.class),
            mock(CheckpointViewRepository.class), mock(SealService.class), reports, mock(ProgressNotifier.class),
            purges);

    @Test
    @DisplayName("이벤트 시리얼이 TEST-여도 저장된 시리얼이 실제 기기면 봉인 기록을 지우지 않는다")
    void realSerialIsKept() throws Exception {
        when(reports.findByRentalId(RENTAL)).thenReturn(Optional.of(report("C02XK1JHMD6R")));
        assertThat(ingest.purged(envelope())).isFalse();
        verify(purges, never()).deleteRental(anyString());
    }

    @Test
    @DisplayName("저장된 시리얼이 TEST-면 지운다")
    void testSerialIsPurged() throws Exception {
        when(reports.findByRentalId(RENTAL)).thenReturn(Optional.of(report("TEST-0002")));
        when(purges.deleteRental(RENTAL)).thenReturn(3);
        assertThat(ingest.purged(envelope())).isTrue();
    }

    private static Envelope envelope() {
        return new Envelope("evt_00000000000000001", "trace.checkpoint.purged", RENTAL, "cp_000000000000000001",
                OffsetDateTime.parse("2026-11-08T10:00:00Z"));
    }

    private static RentalReport report(String serial) throws Exception {
        Constructor<RentalReport> c = RentalReport.class.getDeclaredConstructor();
        c.setAccessible(true);
        RentalReport r = c.newInstance();
        r.recordDevice("dev_00000000000000001", serial, "MBA13-M2", "MacBook Air 13 M2");
        return r;
    }
}

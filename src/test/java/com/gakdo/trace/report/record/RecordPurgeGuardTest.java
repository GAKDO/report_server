// 이 파일이 확인하는 것: 시험 렌탈 지우기의 안전장치.
// 이벤트에 적힌 일련번호만 믿지 않고, report-server에 "처음 저장된" 기기 일련번호가 TEST-일 때만 지운다.

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

    // 테스트에 쓰는 렌탈 번호
    private static final String RENTAL = "rental_0000000000001";
    // (도구) mock(클래스) = 진짜 일은 안 하고 정해 준 대로만 답하며, 불린 기록을 남기는 가짜(대역) 객체
    // 리포트 저장소를 가짜로 만든다(저장된 일련번호를 테스트에서 정하려고)
    private final RentalReportRepository reports = mock(RentalReportRepository.class);
    // 기록 지우기 도구를 가짜로 만든다(지웠는지 보려고)
    private final RecordPurgeStore purges = mock(RecordPurgeStore.class);
    // 나머지 의존 객체도 가짜로 넣어 이벤트 처리 서비스를 만든다
    private final RecordIngestService ingest = new RecordIngestService(mock(ProcessedEventRepository.class),
            mock(CheckpointViewRepository.class), mock(SealService.class), reports, mock(ProgressNotifier.class),
            purges);

    // [상황] 지움 이벤트는 TEST-라고 하지만 저장된 일련번호는 실제 맥북(C02...)이다.
    // [기대] 봉인 기록을 지우지 않는다.
    @Test
    @DisplayName("이벤트 시리얼이 TEST-여도 저장된 시리얼이 실제 기기면 봉인 기록을 지우지 않는다")
    void realSerialIsKept() throws Exception {
        // (도구) when(...).thenReturn(값) = 그 메서드가 불리면 이 값을 돌려주라고 가짜에게 정해 둔다
        // 준비: 저장된 일련번호가 실제 맥북 번호인 리포트를 돌려주게 한다
        when(reports.findByRentalId(RENTAL)).thenReturn(Optional.of(report("C02XK1JHMD6R")));
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 실행+확인: 지움 이벤트를 처리해도 지우지 않았다고(false) 답하는지 확인한다
        assertThat(ingest.purged(envelope())).isFalse();
        // (도구) verify(가짜, never()).메서드(anyString()) = 그 메서드가 어떤 글자로도 한 번도 불리지 않았는지 확인한다
        // 지우기가 한 번도 불리지 않았는지 확인한다
        verify(purges, never()).deleteRental(anyString());
    }

    // [상황] 저장된 일련번호가 TEST-0002다.
    // [기대] 그 렌탈 기록을 지운다.
    @Test
    @DisplayName("저장된 시리얼이 TEST-면 지운다")
    void testSerialIsPurged() throws Exception {
        // 준비: 저장된 일련번호가 TEST-0002인 리포트를 돌려주게 한다
        when(reports.findByRentalId(RENTAL)).thenReturn(Optional.of(report("TEST-0002")));
        // 지우면 3줄을 지웠다고 답하게 한다
        when(purges.deleteRental(RENTAL)).thenReturn(3);
        // 실행+확인: 지움 이벤트를 처리하면 지웠다고(true) 답하는지 확인한다
        assertThat(ingest.purged(envelope())).isTrue();
    }

    // [상황] 리포트는 있는데 저장된 일련번호를 아직 모른다.
    // [기대] 확실하지 않으니 지우지 않는다.
    @Test
    @DisplayName("리포트는 있는데 저장된 시리얼을 아직 모르면 지우지 않는다")
    void unknownSerialIsKept() throws Exception {
        // 준비: 일련번호가 비어 있는 리포트를 돌려주게 한다
        when(reports.findByRentalId(RENTAL)).thenReturn(Optional.of(report(null)));
        // 실행+확인: 지우지 않았다고 답하는지 확인한다
        assertThat(ingest.purged(envelope())).isFalse();
        // 지우기가 불리지 않았는지 확인한다
        verify(purges, never()).deleteRental(anyString());
    }

    // [상황] 실제 일련번호가 적힌 뒤, 나중에 온 시점 시작 이벤트가 TEST- 일련번호를 보낸다.
    // [기대] 처음 적힌 실제 일련번호가 바뀌지 않아, 이 꼼수로 실제 기록을 지울 수 없다.
    @Test
    @DisplayName("뒤에 온 시점 시작 이벤트가 TEST- 시리얼을 보내도 처음 적힌 실제 시리얼은 바뀌지 않는다")
    void laterStartedCannotRewriteSerial() throws Exception {
        // 준비: 실제 일련번호가 처음 적힌 리포트를 만든다
        RentalReport r = report("C02XK1JHMD6R");
        // 나중에 온 시점 시작 이벤트처럼 TEST- 일련번호로 기기 정보를 다시 적는다
        r.recordDevice("dev_00000000000000001", "TEST-0002", "MBA13-M2", "MacBook Air 13 M2");
        // 처음 적힌 실제 일련번호가 그대로인지 먼저 확인한다
        assertThat(r.serial()).isEqualTo("C02XK1JHMD6R");
        // 그 리포트를 돌려주게 한다
        when(reports.findByRentalId(RENTAL)).thenReturn(Optional.of(r));
        // 실행+확인: 지움 이벤트를 처리해도 지우지 않는지 확인한다
        assertThat(ingest.purged(envelope())).isFalse();
        // 지우기가 불리지 않았는지 확인한다
        verify(purges, never()).deleteRental(anyString());
    }

    // 테스트 도구: 이 렌탈의 "시험 렌탈 지움" 이벤트 봉투를 만든다
    private static Envelope envelope() {
        // 이벤트 ID·종류·렌탈·시점·발생 시각을 담아 돌려준다
        return new Envelope("evt_00000000000000001", "trace.checkpoint.purged", RENTAL, "cp_000000000000000001",
                OffsetDateTime.parse("2026-11-08T10:00:00Z"));
    }

    // 테스트 도구: 주어진 일련번호가 적힌 리포트를 만든다
    private static RentalReport report(String serial) throws Exception {
        // (도구) 리플렉션 = 코드 밖에서 숨은(private) 생성자·칸을 찾아 쓰는 자바 기능
        // 리포트의 숨은 기본 생성자를 찾는다(보통은 DB가 쓰는 생성자라서)
        Constructor<RentalReport> c = RentalReport.class.getDeclaredConstructor();
        // private이라도 부를 수 있게 연다
        c.setAccessible(true);
        // 빈 리포트를 만든다
        RentalReport r = c.newInstance();
        // 기기 정보(일련번호 포함)를 적는다
        r.recordDevice("dev_00000000000000001", serial, "MBA13-M2", "MacBook Air 13 M2");
        // 리포트를 돌려준다
        return r;
    }
}

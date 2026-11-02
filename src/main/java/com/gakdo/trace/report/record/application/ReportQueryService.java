package com.gakdo.trace.report.record.application;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.report.common.domain.DomainException;
import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.record.domain.ChainCheck;
import com.gakdo.trace.report.record.domain.ChainRecord;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.ManualSealLog;
import com.gakdo.trace.report.record.domain.RentalReport;
import com.gakdo.trace.report.record.infra.ChainRecordRepository;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;
import com.gakdo.trace.report.record.infra.ManualSealLogRepository;
import com.gakdo.trace.report.record.infra.RentalReportRepository;

/** 타임라인·체인·공개 검증·수동 봉인 기록 읽기. 쓰기는 없다. */
@Service
@Transactional(readOnly = true)
public class ReportQueryService {

    /** 타임라인 결과. chainStatus는 codes.yaml report.chain_status */
    public record Timeline(String rentalId, String modelCode, String chainStatus, String verifyUrl, List<CheckpointView> checkpoints) {
    }

    public record Chain(String rentalId, boolean valid, List<ChainRecord> records) {
    }

    public record Verification(boolean valid, String modelName, List<ChainRecord> records, OffsetDateTime checkedAt) {
    }

    public record ManualSeals(List<ManualSealLog> items, String nextCursor) {
    }

    private final CheckpointViewRepository views;
    private final ChainRecordRepository chain;
    private final RentalReportRepository reports;
    private final ManualSealLogRepository manualLogs;
    private final ChainVerifier verifier;
    private final String webPublicUrl;

    public ReportQueryService(CheckpointViewRepository views, ChainRecordRepository chain,
            RentalReportRepository reports, ManualSealLogRepository manualLogs, ChainVerifier verifier,
            @Value("${app.web-public-url}") String webPublicUrl) {
        this.views = views;
        this.chain = chain;
        this.reports = reports;
        this.manualLogs = manualLogs;
        this.verifier = verifier;
        this.webPublicUrl = webPublicUrl.endsWith("/") ? webPublicUrl.substring(0, webPublicUrl.length() - 1)
                : webPublicUrl;
    }

    /** 시점 종류를 아직 모르는 시점(업로드만 받음)은 등급이 오면 보인다. 출고 → 수령 → 반납 순. */
    public Timeline timeline(String rentalId) {
        RentalReport report = reports.findByRentalId(rentalId).orElseThrow(() -> DomainException.notFound("렌탈 리포트"));
        List<CheckpointView> typed = views.findByRentalIdOrderByIdAsc(rentalId).stream()
                .filter(v -> v.checkpointType() != null)
                .sorted(Comparator.comparing(CheckpointView::checkpointType))
                .toList();
        List<ChainRecord> records = chain.findByRentalIdOrderBySeqAsc(rentalId);
        String status = records.isEmpty() ? "PENDING" : verifier.verify(rentalId, records).valid() ? "VALID" : "BROKEN";
        String verifyUrl = records.isEmpty() ? null : webPublicUrl + "/verify/" + report.verifyToken();
        return new Timeline(rentalId, orEmpty(report.modelCode()), status, verifyUrl, typed);
    }

    public Chain chain(String rentalId) {
        reports.findByRentalId(rentalId).orElseThrow(() -> DomainException.notFound("렌탈 리포트"));
        List<ChainRecord> records = chain.findByRentalIdOrderBySeqAsc(rentalId);
        return new Chain(rentalId, verifier.verify(rentalId, records).valid(), records);
    }

    /** 공개 검증: 토큰을 모르면 404. 봉인 전 토큰은 밖에 나가지 않는다. */
    public Optional<Verification> verify(String verifyToken) {
        return reports.findByVerifyToken(verifyToken).map(report -> {
            List<ChainRecord> records = chain.findByRentalIdOrderBySeqAsc(report.rentalId());
            ChainCheck.Result result = verifier.verify(report.rentalId(), records);
            return new Verification(result.valid(), orEmpty(report.modelName()), records, Times.now());
        });
    }

    public ManualSeals manualSeals(int limit, String cursor) {
        PageRequest page = PageRequest.of(0, limit + 1);
        List<ManualSealLog> rows = cursor == null || cursor.isBlank() ? manualLogs.findAllByOrderByIdDesc(page)
                : manualLogs.findByIdLessThanOrderByIdDesc(decodeCursor(cursor), page);
        if (rows.size() <= limit) {
            return new ManualSeals(rows, null);
        }
        List<ManualSealLog> items = rows.subList(0, limit);
        return new ManualSeals(List.copyOf(items), encodeCursor(items.get(items.size() - 1).id()));
    }

    /** checkpoint.started가 아직 안 왔으면(1.0 생산자) 계약의 필수 문자열을 빈 값으로 채운다. */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("m:" + id).getBytes(StandardCharsets.UTF_8));
    }

    static long decodeCursor(String cursor) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!text.startsWith("m:")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(text.substring(2));
        } catch (IllegalArgumentException e) {
            throw DomainException.invalid("cursor", "cursor가 올바르지 않습니다.");
        }
    }
}

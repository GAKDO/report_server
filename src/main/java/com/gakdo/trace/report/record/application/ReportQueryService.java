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

    /** 기록 검색 한 줄. chainStatus는 codes.yaml report.chain_status */
    public record SearchHit(String rentalId, String serial, String modelCode, String chainStatus,
            OffsetDateTime updatedAt) {
    }

    public record SearchPage(List<SearchHit> items, String nextCursor) {
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
        String status = chainStatus(rentalId, records);
        String verifyUrl = records.isEmpty() ? null : webPublicUrl + "/verify/" + report.verifyToken();
        return new Timeline(rentalId, orEmpty(report.modelCode()), status, verifyUrl, typed);
    }

    /** 봉인 기록이 없으면 PENDING, 있으면 검증 결과에 따라 VALID·BROKEN. */
    private String chainStatus(String rentalId, List<ChainRecord> records) {
        if (records.isEmpty()) {
            return "PENDING";
        }
        return verifier.verify(rentalId, records).valid() ? "VALID" : "BROKEN";
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

    /**
     * 시리얼·렌탈 ID 일부로 렌탈 기록을 찾는다(FR-RPT-05, 관리자). LIKE 특수문자는 글자로 본다.
     * 커서는 마지막 줄의 내부 id라 새 기록이 생겨도 다음 쪽이 밀리지 않는다.
     */
    @Transactional(readOnly = true)
    public SearchPage search(String q, int limit, String cursor) {
        String needle = q == null ? "" : q.strip();
        if (needle.length() < 2 || needle.length() > 64) {
            throw DomainException.invalid("q", "검색어는 2~64자입니다.");
        }
        String pattern = "%" + needle.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        long before = cursor == null || cursor.isBlank() ? Long.MAX_VALUE : decodeCursor(cursor, "s:");
        List<RentalReport> rows = reports.search(pattern, before, PageRequest.of(0, limit + 1));
        List<RentalReport> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        List<SearchHit> items = page.stream().map(r -> new SearchHit(r.rentalId(), orEmpty(r.serial()),
                orEmpty(r.modelCode()), chainStatus(r.rentalId(), chain.findByRentalIdOrderBySeqAsc(r.rentalId())),
                r.updatedAt())).toList();
        String next = rows.size() > limit ? encodeCursor(page.get(page.size() - 1).id(), "s:") : null;
        return new SearchPage(items, next);
    }

    /** checkpoint.started가 아직 안 왔으면(1.0 생산자) 계약의 필수 문자열을 빈 값으로 채운다. */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    static String encodeCursor(long id) {
        return encodeCursor(id, "m:");
    }

    static long decodeCursor(String cursor) {
        return decodeCursor(cursor, "m:");
    }

    private static String encodeCursor(long id, String kind) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((kind + id).getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeCursor(String cursor, String kind) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!text.startsWith(kind)) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(text.substring(kind.length()));
        } catch (IllegalArgumentException e) {
            throw DomainException.invalid("cursor", "cursor가 올바르지 않습니다.");
        }
    }
}

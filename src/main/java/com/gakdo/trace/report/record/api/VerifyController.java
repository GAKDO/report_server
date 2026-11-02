package com.gakdo.trace.report.record.api;

import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.report.common.domain.DomainException;
import com.gakdo.trace.report.record.api.ReportDtos.VerifyRecordResponse;
import com.gakdo.trace.report.record.api.ReportDtos.VerifyResponse;
import com.gakdo.trace.report.record.application.ReportQueryService;

/** 공개 검증(FR-INT-02 서버). 로그인 없이 체인 정상 여부·시점별 등급·지문·서명 시각만. 형식이 틀린 토큰도 404. */
@RestController
public class VerifyController {

    private static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{32}$");

    private final ReportQueryService queries;

    public VerifyController(ReportQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/verify/{verifyToken}")
    public VerifyResponse verify(@PathVariable String verifyToken) {
        if (!TOKEN.matcher(verifyToken).matches()) {
            throw notFound();
        }
        ReportQueryService.Verification v = queries.verify(verifyToken).orElseThrow(VerifyController::notFound);
        // 기종 이름은 이벤트에 아직 없다(계약 1.1 결정 대기). 그 전에는 빈 문자열
        return new VerifyResponse(v.valid(), "", v.records().stream().map(VerifyRecordResponse::of).toList(),
                v.checkedAt());
    }

    private static DomainException notFound() {
        return new DomainException(com.gakdo.trace.report.common.domain.ErrorCode.NOT_FOUND, "검증할 기록을 찾을 수 없습니다.");
    }
}

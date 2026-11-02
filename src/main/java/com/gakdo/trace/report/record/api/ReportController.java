package com.gakdo.trace.report.record.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.report.common.infra.Caller;
import com.gakdo.trace.report.common.infra.PublicIds;
import com.gakdo.trace.report.record.api.ReportDtos.ChainRecordResponse;
import com.gakdo.trace.report.record.api.ReportDtos.ChainResponse;
import com.gakdo.trace.report.record.api.ReportDtos.ManualSealItem;
import com.gakdo.trace.report.record.api.ReportDtos.ManualSealPage;
import com.gakdo.trace.report.record.api.ReportDtos.ManualSealRequest;
import com.gakdo.trace.report.record.api.ReportDtos.RentalTimelineResponse;
import com.gakdo.trace.report.record.api.ReportDtos.TimelineCheckpointResponse;
import com.gakdo.trace.report.record.application.ReportQueryService;
import com.gakdo.trace.report.record.application.SealService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** 타임라인(FR-RPT-03)·해시 체인(FR-INT-01)·관리자 수동 봉인(11/2 결정). 역할은 SecurityConfig, 렌탈 범위는 Caller. */
@RestController
@Validated
public class ReportController {

    private final ReportQueryService queries;
    private final SealService seals;

    public ReportController(ReportQueryService queries, SealService seals) {
        this.queries = queries;
        this.seals = seals;
    }

    @GetMapping("/api/v1/reports/rentals/{rentalId}")
    public RentalTimelineResponse timeline(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId,
            @AuthenticationPrincipal Jwt jwt) {
        Caller.from(jwt).requireRentalReader(rentalId);
        ReportQueryService.Timeline t = queries.timeline(rentalId);
        return new RentalTimelineResponse(t.rentalId(), t.modelCode(), t.chainStatus(), t.verifyUrl(),
                t.checkpoints().stream().map(TimelineCheckpointResponse::of).toList());
    }

    @GetMapping("/api/v1/reports/rentals/{rentalId}/chain")
    public ChainResponse chain(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId) {
        ReportQueryService.Chain c = queries.chain(rentalId);
        return new ChainResponse(c.rentalId(), c.valid(), c.records().stream().map(ChainRecordResponse::of).toList());
    }

    @PostMapping("/api/v1/reports/rentals/{rentalId}/checkpoints/{checkpointId}/seal")
    @ResponseStatus(HttpStatus.CREATED)
    public ChainRecordResponse sealManually(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId,
            @Valid @RequestBody ManualSealRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ChainRecordResponse.of(seals.sealManually(rentalId, checkpointId, request.checkpointType(),
                request.grade(), request.reason(), jwt.getSubject()));
    }

    @GetMapping("/api/v1/reports/manual-seals")
    public ManualSealPage manualSeals(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) {
        ReportQueryService.ManualSeals page = queries.manualSeals(limit, cursor);
        return new ManualSealPage(page.items().stream().map(ManualSealItem::of).toList(), page.nextCursor());
    }
}

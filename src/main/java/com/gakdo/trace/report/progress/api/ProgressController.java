package com.gakdo.trace.report.progress.api;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gakdo.trace.report.common.infra.Caller;
import com.gakdo.trace.report.common.infra.PublicIds;
import com.gakdo.trace.report.progress.application.ProgressStreams;

import jakarta.validation.constraints.Pattern;

/** 검사 진행률 SSE(FR-RPT-04, contracts streamRentalProgress). 범위는 연결 전에 Caller로 확인해 403을 상태 코드로 돌려준다. */
@RestController
@Validated
public class ProgressController {

    private final ProgressStreams streams;

    public ProgressController(ProgressStreams streams) {
        this.streams = streams;
    }

    @GetMapping(path = "/api/v1/reports/rentals/{rentalId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId,
            @AuthenticationPrincipal Jwt jwt) {
        String checkpointScope = Caller.from(jwt).requireProgressReader(rentalId);
        return streams.open(rentalId, checkpointScope);
    }
}

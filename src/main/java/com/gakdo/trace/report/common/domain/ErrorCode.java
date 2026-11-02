package com.gakdo.trace.report.common.domain;

import org.springframework.http.HttpStatus;

/**
 * 이 서버가 내는 오류 code. 값은 contracts codes.yaml {@code error_code}와 같아야 한다.
 * 제목은 화면이 그대로 보여 줄 수 있는 한국어 짧은 문장이다.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "본인 렌탈만 볼 수 있습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
    CHECKPOINT_ORDER_VIOLATION(HttpStatus.CONFLICT, "앞 시점이 아직 봉인되지 않았습니다"),
    RECORD_ALREADY_SEALED(HttpStatus.CONFLICT, "이미 봉인된 시점입니다");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}

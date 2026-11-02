package com.gakdo.trace.report.record.domain;

/** 해시 체인 재료 한 줄(trace.checkpoint.graded photos[]). */
public record HashRef(String photoId, String sha256) {
}

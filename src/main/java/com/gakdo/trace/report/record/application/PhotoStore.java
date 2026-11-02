package com.gakdo.trace.report.record.application;

import java.util.Optional;

/** 봉인한 사진이 지금 저장소에 어떤 내용으로 있는지. 시험에서는 가짜로 바꿔 끼운다. */
public interface PhotoStore {

    /** @return 지금 객체의 SHA-256(16진수). 객체가 없으면 빈 값 */
    Optional<String> currentSha256(String objectKey);
}

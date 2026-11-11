package com.gakdo.trace.report.record.domain;

/**
 * 3D 리포트의 흠집 핀(checkpoint.graded 1.1의 pins). 화면용이라 해시 체인 재료에는 넣지 않는다.
 * x·y는 그 면의 표준 좌표(px), isNew는 newDefectIds에 있으면 true.
 */
public record Pin(String defectId, String face, double x, double y, boolean isNew) {
}

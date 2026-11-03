package com.gakdo.trace.report.progress.domain;

/**
 * 진행률 단계(codes.yaml report.progress_stage)와 화면에 보낼 퍼센트. 계약 예시(INSPECTED 60)에 맞춰 단계마다 20씩 올린다.
 * ALIGNED는 inspect-worker가 Redis로 면별 진행을 올릴 때 쓰고, 이벤트만으로는 INSPECTED에서 함께 끝난다.
 */
public enum ProgressStage {
    UPLOADED(20), ALIGNED(40), INSPECTED(60), GRADED(80), SEALED(100);

    private final int percent;

    ProgressStage(int percent) {
        this.percent = percent;
    }

    public int percent() {
        return percent;
    }
}

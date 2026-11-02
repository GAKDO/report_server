package com.gakdo.trace.report.record.domain;

/** 시점 종류(codes.yaml rental.checkpoint_type). 체인은 이 순서로만 잇는다: 출고 → 수령 → 반납. */
public enum CheckpointType {
    OUT, RCV, RET;

    /** 체인에서 이 시점의 순번(1부터). 렌탈마다 시점 종류는 하나씩이라 seq와 같다. */
    public int seq() {
        return ordinal() + 1;
    }
}

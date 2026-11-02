package com.gakdo.trace.report.record.domain;

/** 시점 상태(codes.yaml rental.checkpoint_status). report-server는 받은 이벤트만큼만 안다. */
public enum CheckpointStatus {
    OPEN, CAPTURED, GRADED, SEALED;

    /** 상태는 앞으로만 간다. 늦게 온 이벤트가 상태를 되돌리지 않게 한다. */
    public CheckpointStatus advanceTo(CheckpointStatus next) {
        return next.ordinal() > ordinal() ? next : this;
    }
}

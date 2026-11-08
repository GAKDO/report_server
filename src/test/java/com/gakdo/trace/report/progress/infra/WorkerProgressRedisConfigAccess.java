package com.gakdo.trace.report.progress.infra;

import org.springframework.data.redis.connection.MessageListener;

import com.gakdo.trace.report.progress.application.WorkerProgress;

/** 시험용: 패키지 안의 리스너 만들기를 밖 패키지 테스트에서 부른다. */
public final class WorkerProgressRedisConfigAccess {

    private WorkerProgressRedisConfigAccess() {
    }

    public static MessageListener listener(WorkerProgress progress, String prefix) {
        return WorkerProgressRedisConfig.listener(progress, prefix);
    }
}

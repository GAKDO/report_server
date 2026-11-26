// 테스트 도구(테스트 자체는 아님): 패키지 안에 숨겨진 Redis 리스너를 다른 패키지의 테스트에서 만들 수 있게 열어 준다.

package com.gakdo.trace.report.progress.infra;

import org.springframework.data.redis.connection.MessageListener;

import com.gakdo.trace.report.progress.application.WorkerProgress;

/** 시험용: 패키지 안의 리스너 만들기를 밖 패키지 테스트에서 부른다. */
public final class WorkerProgressRedisConfigAccess {

    // 객체를 만들 일이 없는 도구라 생성자를 막아 둔다
    private WorkerProgressRedisConfigAccess() {
    }

    // 테스트 도구: 진행률 객체와 키 접두사로 Redis 메시지 리스너를 만든다
    public static MessageListener listener(WorkerProgress progress, String prefix) {
        // 같은 패키지에 있어 부를 수 있는 숨은 메서드를 대신 불러 돌려준다
        return WorkerProgressRedisConfig.listener(progress, prefix);
    }
}

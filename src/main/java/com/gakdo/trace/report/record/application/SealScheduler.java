package com.gakdo.trace.report.record.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;

/** 등급이 나왔는데 진단이 오지 않은 시점을 기다리는 시간이 지나면 봉인한다. 한 시점이 실패해도 나머지는 계속한다. */
@Component
public class SealScheduler {

    private static final Logger log = LoggerFactory.getLogger(SealScheduler.class);

    private final CheckpointViewRepository views;
    private final SealService seals;

    public SealScheduler(CheckpointViewRepository views, SealService seals) {
        this.views = views;
        this.seals = seals;
    }

    @Scheduled(fixedDelayString = "${app.chain.sweep-interval:PT15S}", initialDelayString = "${app.chain.sweep-interval:PT15S}")
    public void sealDue() {
        var now = Times.now();
        for (String checkpointId : views.findDueCheckpointIds(now.minus(seals.diagnosticsWait()))) {
            try {
                seals.sealIfReady(checkpointId, now);
            } catch (RuntimeException e) {
                log.error("자동 봉인 실패: checkpointId={}", checkpointId, e);
            }
        }
    }
}

package com.gakdo.trace.report.progress.application;

import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;

/**
 * 렌탈별 SSE 연결을 들고 있다가 진행 단계가 바뀌면 흘려보낸다(FR-RPT-04, contracts streamRentalProgress).
 * 붙자마자 지금 단계를 먼저 보내서, 끊겼다 다시 붙은 화면도 마지막 상태부터 이어 간다.
 * 촬영 JWT(scope = checkpointId)는 그 시점의 진행률만 받는다. report-server는 1개 파드라 연결을 메모리에 둔다.
 */
@Service
public class ProgressStreams {

    private static final Logger log = LoggerFactory.getLogger(ProgressStreams.class);

    /** 연결 하나. checkpointScope가 있으면 그 시점만 보낸다. */
    record Subscriber(SseEmitter emitter, String checkpointScope) {
        boolean wants(ProgressUpdate update) {
            return checkpointScope == null || checkpointScope.equals(update.checkpointId());
        }
    }

    private final Map<String, Set<Subscriber>> byRental = new ConcurrentHashMap<>();
    private final CheckpointViewRepository views;
    private final Duration timeout;

    public ProgressStreams(CheckpointViewRepository views,
            @Value("${app.progress.stream-timeout:PT30M}") Duration timeout) {
        this.views = views;
        this.timeout = timeout;
    }

    /** 연결을 열고 지금 단계를 먼저 보낸다. 시간이 다 되면 닫히고, 화면은 스스로 다시 붙는다. */
    @Transactional(readOnly = true)
    public SseEmitter open(String rentalId, String checkpointScope) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        Subscriber subscriber = new Subscriber(emitter, checkpointScope);
        Set<Subscriber> set = byRental.computeIfAbsent(rentalId, k -> new CopyOnWriteArraySet<>());
        set.add(subscriber);
        Runnable remove = () -> remove(rentalId, subscriber);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        views.findByRentalIdOrderByIdAsc(rentalId).stream()
                .map(ProgressUpdate::of)
                .flatMap(java.util.Optional::stream)
                .filter(subscriber::wants)
                .forEach(update -> send(rentalId, subscriber, update));
        return emitter;
    }

    /** 읽기 모델 트랜잭션이 커밋된 뒤에만 보낸다(롤백된 단계가 화면에 보이지 않게). */
    @TransactionalEventListener(fallbackExecution = true)
    public void onUpdate(ProgressUpdate update) {
        for (Subscriber subscriber : byRental.getOrDefault(update.rentalId(), Set.of())) {
            if (subscriber.wants(update)) {
                send(update.rentalId(), subscriber, update);
            }
        }
    }

    /** 15초마다 주석 줄을 보내 프록시가 연결을 끊지 않게 한다(계약). */
    @Scheduled(fixedRateString = "${app.progress.keep-alive:PT15S}", initialDelayString = "${app.progress.keep-alive:PT15S}")
    public void keepAlive() {
        byRental.forEach((rentalId, set) -> set.forEach(subscriber -> {
            try {
                subscriber.emitter().send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                remove(rentalId, subscriber);
            }
        }));
    }

    int connections(String rentalId) {
        return byRental.getOrDefault(rentalId, Set.of()).size();
    }

    private void send(String rentalId, Subscriber subscriber, ProgressUpdate update) {
        try {
            if (update.done()) {
                subscriber.emitter().send(SseEmitter.event().name("done").data(doneData(update), MediaType.APPLICATION_JSON));
            } else {
                subscriber.emitter().send(SseEmitter.event().name("progress")
                        .data(progressData(update), MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("끊긴 진행률 연결을 정리합니다: rentalId={}", rentalId);
            remove(rentalId, subscriber);
        }
    }

    /** contracts ProgressEvent */
    static Map<String, Object> progressData(ProgressUpdate update) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkpointId", update.checkpointId());
        data.put("stage", update.stage().name());
        data.put("percent", update.stage().percent());
        data.put("at", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(update.at() == null ? Times.now() : update.at()));
        return data;
    }

    /** contracts DoneEvent */
    static Map<String, Object> doneData(ProgressUpdate update) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkpointId", update.checkpointId());
        data.put("grade", update.grade() == null ? null : update.grade().name());
        return data;
    }

    private void remove(String rentalId, Subscriber subscriber) {
        byRental.computeIfPresent(rentalId, (k, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }

    /** 시험용: 지금 붙은 렌탈 */
    List<String> rentals() {
        return List.copyOf(byRental.keySet());
    }
}

package com.gakdo.trace.report.record.application;

import java.util.Map;

/** trace.record.sealed를 낸다. DB 트랜잭션이 커밋될 때 함께 나간다(채널 트랜잭션). */
public interface SealedEventPublisher {

    String ROUTING_KEY = "trace.record.sealed";

    void publish(String eventId, Map<String, Object> event);
}

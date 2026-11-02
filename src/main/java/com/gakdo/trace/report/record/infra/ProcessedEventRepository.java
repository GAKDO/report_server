package com.gakdo.trace.report.record.infra;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.gakdo.trace.report.record.domain.CheckpointView;

/** 처리한 eventId(report.processed_event). 같은 트랜잭션에서 넣어 처리와 함께 커밋·롤백된다. */
public interface ProcessedEventRepository extends Repository<CheckpointView, Long> {

    /** @return 처음 본 eventId면 1, 이미 처리했으면 0 */
    @Modifying
    @Query(value = "INSERT INTO report.processed_event (event_id, routing_key) VALUES (:eventId, :routingKey)"
            + " ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int markProcessed(@Param("eventId") String eventId, @Param("routingKey") String routingKey);
}

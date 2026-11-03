package com.gakdo.trace.report.record.infra;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.report.record.domain.CheckpointView;

public interface CheckpointViewRepository extends JpaRepository<CheckpointView, Long> {

    Optional<CheckpointView> findByCheckpointId(String checkpointId);

    /** 잠글 렌탈 줄을 알려고 읽는다(엔티티를 올리지 않음). */
    @Query("select v.rentalId from CheckpointView v where v.checkpointId = :checkpointId")
    Optional<String> findRentalIdByCheckpointId(String checkpointId);

    List<CheckpointView> findByRentalIdOrderByIdAsc(String rentalId);

    /** 등급은 나왔지만 진단을 기다리다 시간이 지난 시점(봉인 대상). */
    @Query("select v.checkpointId from CheckpointView v where v.status = com.gakdo.trace.report.record.domain.CheckpointStatus.GRADED"
            + " and v.gradedAt <= :before order by v.gradedAt")
    List<String> findDueCheckpointIds(OffsetDateTime before);
}

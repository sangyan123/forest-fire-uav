package com.forestfire.uav.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 调度记录表 Repository */
public interface DispatchRecordRepository extends JpaRepository<DispatchRecordEntity, UUID> {

    /** 取 mission 最新一次调度（任务详情用） */
    Optional<DispatchRecordEntity> findFirstByMissionIdOrderByDecisionTimeDesc(UUID missionId);
}

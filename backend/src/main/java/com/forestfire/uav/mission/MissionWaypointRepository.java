package com.forestfire.uav.mission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 任务航点表 Repository */
public interface MissionWaypointRepository extends JpaRepository<MissionWaypointEntity, UUID> {

    /** 按 mission 取航点（sequence_no 升序） */
    List<MissionWaypointEntity> findByMissionIdOrderBySequenceNoAsc(UUID missionId);
}

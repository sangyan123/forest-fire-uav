package com.forestfire.uav.mission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 任务表 Repository */
public interface MissionRepository extends JpaRepository<MissionEntity, UUID> {

    /** 列表：按创建倒序 */
    List<MissionEntity> findAllByOrderByCreatedAtDesc();

    /** 按 mission_no 查询（宽容解析预留） */
    Optional<MissionEntity> findByMissionNo(String missionNo);

    /** 序号生成：当日 mission_no 前缀计数 */
    long countByMissionNoStartingWith(String prefix);
}

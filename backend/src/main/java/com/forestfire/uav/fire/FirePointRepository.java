package com.forestfire.uav.fire;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 火点表 Repository（id 主键查询由 JpaRepository 自带） */
public interface FirePointRepository extends JpaRepository<FirePointEntity, UUID> {

    /** 按 incident 取全部火点（detected_at 倒序，详情页用） */
    List<FirePointEntity> findByIncidentIdOrderByDetectedAtDesc(UUID incidentId);
}

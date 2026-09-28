package com.forestfire.uav.fire;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 火场多边形表 Repository */
public interface FirePolygonRepository extends JpaRepository<FirePolygonEntity, UUID> {

    /** growthStep = 已有 fire_polygon 行数 */
    long countByIncidentId(UUID incidentId);

    /** 历史多边形（created_at 升序，前端叠加动画用） */
    List<FirePolygonEntity> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);
}

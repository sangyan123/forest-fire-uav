package com.forestfire.uav.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 风险区域评估版本表 Repository（只插不改，UNIQUE area_id+assessed_at） */
public interface RiskAreaAssessmentRepository extends JpaRepository<RiskAreaAssessmentEntity, UUID> {
}

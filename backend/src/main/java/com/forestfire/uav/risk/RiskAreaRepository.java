package com.forestfire.uav.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 风险区域表 Repository */
public interface RiskAreaRepository extends JpaRepository<RiskAreaEntity, UUID> {

    /** 网格幂等 upsert 键 */
    Optional<RiskAreaEntity> findByAreaCode(String areaCode);

    /** 列表/图层：分数降序 */
    List<RiskAreaEntity> findAllByOrderByRiskScoreDesc();

    /** level 过滤（HIGH/MEDIUM/LOW） */
    List<RiskAreaEntity> findByRiskLevelOrderByRiskScoreDesc(String riskLevel);

    /** 评估摘要计数 */
    long countByRiskLevel(String riskLevel);
}

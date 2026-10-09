package com.forestfire.uav.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 巡检建议表 Repository */
public interface PatrolAreaRepository extends JpaRepository<PatrolAreaEntity, UUID> {

    /** 建议列表（新→旧） */
    List<PatrolAreaEntity> findAllByOrderByGeneratedAtDesc();

    /** status 过滤列表 */
    List<PatrolAreaEntity> findByStatusOrderByGeneratedAtDesc(String status);

    /** 生成去重：同区域已存在 SUGGESTED 建议则跳过 */
    Optional<PatrolAreaEntity> findFirstByRiskAreaIdAndStatusOrderByGeneratedAtDesc(
            UUID riskAreaId, String status);

    /** 重评估过期：批量置 EXPIRED 由服务层逐条更新（行数少，避免原生 SQL） */
    List<PatrolAreaEntity> findByStatus(String status);
}

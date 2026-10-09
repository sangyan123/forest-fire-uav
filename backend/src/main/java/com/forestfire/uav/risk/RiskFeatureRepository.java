package com.forestfire.uav.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 风险因子分项表 Repository（每次评估先删后插） */
public interface RiskFeatureRepository extends JpaRepository<RiskFeatureEntity, UUID> {

    List<RiskFeatureEntity> findByRiskAreaIdOrderByContributionDesc(UUID riskAreaId);

    /**
     * 重建因子：删除该区域旧分项（JPQL 批量删除）。
     * @Modifying 查询必须在事务内执行；@Transactional(REQUIRED) 兼容两种调用路径——
     * REST assess() 加入外层事务，启动 ApplicationRunner 自调用 assess() 绕过代理时自建事务。
     */
    @Transactional
    @Modifying
    @Query("delete from RiskFeatureEntity f where f.riskAreaId = :riskAreaId")
    void deleteByRiskAreaId(@Param("riskAreaId") UUID riskAreaId);
}

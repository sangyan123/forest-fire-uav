package com.forestfire.uav.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 灾后评估报告表 Repository（幂等覆盖：一事件一份现行报告） */
public interface AssessmentReportRepository extends JpaRepository<AssessmentReportEntity, UUID> {

    /** 按事件查报告（幂等覆盖后同事件最多一份） */
    Optional<AssessmentReportEntity> findByIncidentId(UUID incidentId);

    /** 列表：按创建倒序 */
    List<AssessmentReportEntity> findAllByOrderByCreatedAtDesc();

    /** 按事件过滤、创建倒序 */
    List<AssessmentReportEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    /**
     * 幂等覆盖：删除该事件的旧报告（JPQL 批量删除）。
     * @Modifying 查询必须在事务内执行；@Transactional 兼容 REST 路径（外层事务）与 Service 自调用两种路径
     * （复用 RiskFeatureRepository.deleteByRiskAreaId 的修复模式，避免自调用绕过代理导致
     * TransactionRequiredException）。
     */
    @Transactional
    @Modifying
    @Query("delete from AssessmentReportEntity r where r.incidentId = :incidentId")
    void deleteByIncidentId(@Param("incidentId") UUID incidentId);
}

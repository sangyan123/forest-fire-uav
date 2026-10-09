package com.forestfire.uav.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 过火分带表 Repository（每报告 3 行 SEVERE/MODERATE/LIGHT，幂等覆盖随报告删旧插新） */
public interface AssessmentAreaRepository extends JpaRepository<AssessmentAreaEntity, UUID> {

    /** 按报告查分带（面积降序：SEVERE 最小但排前为展示靶心由内到外，此处按面积降序便于前端绘制） */
    List<AssessmentAreaEntity> findByReportIdOrderByAreaSquareMeterDesc(UUID reportId);

    /**
     * 幂等覆盖：删除该报告的旧分带（JPQL 批量删除，与报告删除同事务）。
     * @Transactional 同 AssessmentReportRepository.deleteByIncidentId 修复模式。
     */
    @Transactional
    @Modifying
    @Query("delete from AssessmentAreaEntity a where a.reportId = :reportId")
    void deleteByReportId(@Param("reportId") UUID reportId);
}

package com.forestfire.uav.fire;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 火情事件表 Repository */
public interface FireIncidentRepository extends JpaRepository<FireIncidentEntity, UUID> {

    /** 按 incident_no 查询（宽容解析 "INC-..." 编号入参） */
    Optional<FireIncidentEntity> findByIncidentNo(String incidentNo);

    /** 列表：按创建倒序 */
    List<FireIncidentEntity> findAllByOrderByCreatedAtDesc();

    /** 序号生成：当日 incident_no 前缀计数 */
    long countByIncidentNoStartingWith(String prefix);

    /**
     * 火情去重（100m/120s）粗筛：未关闭（status 不在终态集合）、first_detected_at 在窗口内、
     * 经纬度矩形圈选（±100m 对应经纬度增量），随后服务层 Haversine 精算 ≤100m。
     */
    List<FireIncidentEntity> findByStatusNotInAndFirstDetectedAtAfterAndLatitudeBetweenAndLongitudeBetween(
            Collection<String> status,
            Instant firstDetectedAtAfter,
            Double latitudeLowest, Double latitudeGreatest,
            Double longitudeLowest, Double longitudeGreatest);
}

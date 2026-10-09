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

    /** 活跃火情（F11 预测目标：SUSPECTED/VERIFYING/CONFIRMED/TRACKING，最近活跃优先） */
    List<FireIncidentEntity> findByStatusInOrderByUpdatedAtDesc(Collection<String> status);

    /**
     * 火情去重（100m/120s）粗筛：未关闭（status 不在终态集合）、updated_at（事件最后活动时间，
     * 每次检测合并都会刷新）在窗口内、经纬度矩形圈选（±100m 对应经纬度增量），
     * 随后服务层 Haversine 精算 ≤100m。
     * 注意用 updated_at 而非 first_detected_at：持续采集的同一火情，首次检测会早于 120s 窗口，
     * 用 first_detected_at 会把同一火情重复拆成多个事件（D6 彩排实测踩过）。
     */
    List<FireIncidentEntity> findByStatusNotInAndUpdatedAtAfterAndLatitudeBetweenAndLongitudeBetween(
            Collection<String> status,
            Instant updatedAtAfter,
            Double latitudeLowest, Double latitudeGreatest,
            Double longitudeLowest, Double longitudeGreatest);

    /**
     * 误报抑制（D8）粗筛：status=FALSE_ALARM、updated_at（判定落库时间）在抑制窗口内、
     * 经纬度矩形圈选，随后服务层 Haversine 精算 ≤去重距离。
     */
    List<FireIncidentEntity> findByStatusAndUpdatedAtAfterAndLatitudeBetweenAndLongitudeBetween(
            String status,
            Instant updatedAtAfter,
            Double latitudeLowest, Double latitudeGreatest,
            Double longitudeLowest, Double longitudeGreatest);
}

package com.forestfire.uav.mission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * 任务表实体 — 对应 db/migration/V1__baseline_schema.sql 的 mission（18 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                        → id（应用侧 UUID.randomUUID()）</li>
 *   <li>mission_no VARCHAR(64) NOT NULL UNIQUE → mission_no（"MIS-"+yyyyMMdd(UTC)+"-"+4位序号）</li>
 *   <li>mission_type VARCHAR(64) NOT NULL → mission_type（MissionType 六值枚举，
 *       MVP 固定 FIRE_VERIFICATION，校验后落库）</li>
 *   <li>incident_id UUID                  → incident_id（请求体 incidentId，UUID 或 incident_no 宽容解析）</li>
 *   <li>risk_area_id UUID                 → risk_area_id（MVP 不赋值，可空）</li>
 *   <li>patrol_area_id UUID               → patrol_area_id（MVP 不赋值，可空）</li>
 *   <li>assigned_uav_id UUID              → assigned_uav_id（F09 调度选中后回填 device.id）</li>
 *   <li>priority VARCHAR(32)              → priority（请求体；数值按 ≥80→HIGH/≥50→NORMAL/否则 LOW 归一）</li>
 *   <li>status VARCHAR(32) NOT NULL       → status（创建 CREATED；start 后 ASSIGNED→EXECUTING）</li>
 *   <li>planned_start_at TIMESTAMPTZ      → planned_start_at（MVP 不赋值，可空）</li>
 *   <li>planned_end_at TIMESTAMPTZ        → planned_end_at（MVP 不赋值，可空）</li>
 *   <li>actual_start_at TIMESTAMPTZ       → actual_start_at（EXECUTING 时赋值）</li>
 *   <li>actual_end_at TIMESTAMPTZ         → actual_end_at（MVP 不赋值，可空）</li>
 *   <li>origin geometry(Point,4326)       → origin（调度时以 UAV 当前/家位置回填，可空）</li>
 *   <li>target geometry(Point,4326)       → target（请求体 target 的 lat/lon 构造。
 *       任务书"target 存 JSONB"与 DDL 不符——mission 无 JSONB target 列，以 DDL 为准；
 *       altitude/altitudeMode/requiredCapabilities 存 mission_waypoint.action JSONB）</li>
 *   <li>created_by UUID                   → created_by（MVP 无鉴权，可空不赋值）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL   → created_at（应用赋值，NOT NULL 覆盖）</li>
 *   <li>updated_at TIMESTAMPTZ NOT NULL   → updated_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/mission_no/mission_type/status/created_at/updated_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "mission")
public class MissionEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: mission_no VARCHAR(64) NOT NULL UNIQUE — "MIS-"+日期+4位序号 */
    @Column(name = "mission_no", nullable = false)
    private String missionNo;

    /** DDL: mission_type VARCHAR(64) NOT NULL — MissionType 六值枚举 */
    @Column(name = "mission_type", nullable = false)
    private String missionType;

    /** DDL: incident_id UUID — 关联 fire_incident.id（逻辑外键） */
    @Column(name = "incident_id")
    private UUID incidentId;

    /** DDL: risk_area_id UUID（MVP 不赋值，可空） */
    @Column(name = "risk_area_id")
    private UUID riskAreaId;

    /** DDL: patrol_area_id UUID（MVP 不赋值，可空） */
    @Column(name = "patrol_area_id")
    private UUID patrolAreaId;

    /** DDL: assigned_uav_id UUID — F09 调度选中后回填 uav_device.id（逻辑外键） */
    @Column(name = "assigned_uav_id")
    private UUID assignedUavId;

    /** DDL: priority VARCHAR(32) */
    @Column(name = "priority")
    private String priority;

    /** DDL: status VARCHAR(32) NOT NULL — MissionStatus 8 值 */
    @Column(name = "status", nullable = false)
    private String status;

    /** DDL: planned_start_at TIMESTAMPTZ（MVP 不赋值，可空） */
    @Column(name = "planned_start_at")
    private Instant plannedStartAt;

    /** DDL: planned_end_at TIMESTAMPTZ（MVP 不赋值，可空） */
    @Column(name = "planned_end_at")
    private Instant plannedEndAt;

    /** DDL: actual_start_at TIMESTAMPTZ — EXECUTING 时赋值 */
    @Column(name = "actual_start_at")
    private Instant actualStartAt;

    /** DDL: actual_end_at TIMESTAMPTZ（MVP 不赋值，可空） */
    @Column(name = "actual_end_at")
    private Instant actualEndAt;

    /** DDL: origin geometry(Point,4326) — hibernate-spatial 映射（可空，调度时回填） */
    @Column(name = "origin")
    private Point origin;

    /** DDL: target geometry(Point,4326) — hibernate-spatial 映射（可空，创建时构造） */
    @Column(name = "target")
    private Point target;

    /** DDL: created_by UUID（MVP 无鉴权，可空） */
    @Column(name = "created_by")
    private UUID createdBy;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** DDL: updated_at TIMESTAMPTZ NOT NULL */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getMissionNo() { return missionNo; }
    public void setMissionNo(String missionNo) { this.missionNo = missionNo; }
    public String getMissionType() { return missionType; }
    public void setMissionType(String missionType) { this.missionType = missionType; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public UUID getRiskAreaId() { return riskAreaId; }
    public void setRiskAreaId(UUID riskAreaId) { this.riskAreaId = riskAreaId; }
    public UUID getPatrolAreaId() { return patrolAreaId; }
    public void setPatrolAreaId(UUID patrolAreaId) { this.patrolAreaId = patrolAreaId; }
    public UUID getAssignedUavId() { return assignedUavId; }
    public void setAssignedUavId(UUID assignedUavId) { this.assignedUavId = assignedUavId; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getPlannedStartAt() { return plannedStartAt; }
    public void setPlannedStartAt(Instant plannedStartAt) { this.plannedStartAt = plannedStartAt; }
    public Instant getPlannedEndAt() { return plannedEndAt; }
    public void setPlannedEndAt(Instant plannedEndAt) { this.plannedEndAt = plannedEndAt; }
    public Instant getActualStartAt() { return actualStartAt; }
    public void setActualStartAt(Instant actualStartAt) { this.actualStartAt = actualStartAt; }
    public Instant getActualEndAt() { return actualEndAt; }
    public void setActualEndAt(Instant actualEndAt) { this.actualEndAt = actualEndAt; }
    public Point getOrigin() { return origin; }
    public void setOrigin(Point origin) { this.origin = origin; }
    public Point getTarget() { return target; }
    public void setTarget(Point target) { this.target = target; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

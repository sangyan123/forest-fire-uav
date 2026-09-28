package com.forestfire.uav.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 调度记录表实体 — 对应 db/migration/V1__baseline_schema.sql 的 dispatch_record（12 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                        → id（应用侧 UUID.randomUUID()）</li>
 *   <li>mission_id UUID NOT NULL          → mission_id（关联 mission.id）</li>
 *   <li>incident_id UUID                  → incident_id（透传 mission.incident_id）</li>
 *   <li>selected_uav_id UUID              → selected_uav_id（选中 device.id）</li>
 *   <li>algorithm_type VARCHAR(64)        → algorithm_type（"F09-BASIC"，MVP 加权评分）</li>
 *   <li>score DOUBLE PRECISION            → score（距离0.4/电量0.3/能力0.2/通信0.1 加权 0~100）</li>
 *   <li>factors JSONB                     → factors（Map：各因子 raw/normalized + 权重 +
 *       batteryPercent/communicationStatus/selectedReason，留存可解释依据）</li>
 *   <li>estimated_distance DOUBLE PRECISION → estimated_distance（米，Haversine）</li>
 *   <li>estimated_arrival_seconds INTEGER → estimated_arrival_seconds（按 10m/s 巡航速度估算）</li>
 *   <li>decision_time TIMESTAMPTZ NOT NULL → decision_time（应用赋值 now，NOT NULL 覆盖）</li>
 *   <li>result VARCHAR(32)                → result（"ASSIGNED"；无可机为 "NO_CANDIDATE"）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL   → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/mission_id/decision_time/created_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "dispatch_record")
public class DispatchRecordEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: mission_id UUID NOT NULL — 关联 mission.id（逻辑外键） */
    @Column(name = "mission_id", nullable = false)
    private UUID missionId;

    /** DDL: incident_id UUID — 关联 fire_incident.id（逻辑外键，透传） */
    @Column(name = "incident_id")
    private UUID incidentId;

    /** DDL: selected_uav_id UUID — 选中 uav_device.id（逻辑外键） */
    @Column(name = "selected_uav_id")
    private UUID selectedUavId;

    /** DDL: algorithm_type VARCHAR(64) — "F09-BASIC" */
    @Column(name = "algorithm_type")
    private String algorithmType;

    /** DDL: score DOUBLE PRECISION — 加权分 0~100 */
    @Column(name = "score")
    private Double score;

    /** DDL: factors JSONB — 评分因子与权重（可解释依据） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factors")
    private Map<String, Object> factors;

    /** DDL: estimated_distance DOUBLE PRECISION — 预估距离（米） */
    @Column(name = "estimated_distance")
    private Double estimatedDistance;

    /** DDL: estimated_arrival_seconds INTEGER — 预估到达秒数 */
    @Column(name = "estimated_arrival_seconds")
    private Integer estimatedArrivalSeconds;

    /** DDL: decision_time TIMESTAMPTZ NOT NULL */
    @Column(name = "decision_time", nullable = false)
    private Instant decisionTime;

    /** DDL: result VARCHAR(32) — "ASSIGNED" */
    @Column(name = "result")
    private String result;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getMissionId() { return missionId; }
    public void setMissionId(UUID missionId) { this.missionId = missionId; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public UUID getSelectedUavId() { return selectedUavId; }
    public void setSelectedUavId(UUID selectedUavId) { this.selectedUavId = selectedUavId; }
    public String getAlgorithmType() { return algorithmType; }
    public void setAlgorithmType(String algorithmType) { this.algorithmType = algorithmType; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Map<String, Object> getFactors() { return factors; }
    public void setFactors(Map<String, Object> factors) { this.factors = factors; }
    public Double getEstimatedDistance() { return estimatedDistance; }
    public void setEstimatedDistance(Double estimatedDistance) { this.estimatedDistance = estimatedDistance; }
    public Integer getEstimatedArrivalSeconds() { return estimatedArrivalSeconds; }
    public void setEstimatedArrivalSeconds(Integer estimatedArrivalSeconds) { this.estimatedArrivalSeconds = estimatedArrivalSeconds; }
    public Instant getDecisionTime() { return decisionTime; }
    public void setDecisionTime(Instant decisionTime) { this.decisionTime = decisionTime; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.forestfire.uav.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Polygon;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 巡检建议表实体 — 对应 patrol_area（7 列），F08 从风险区域生成（03号第91.3节数据流）。
 * status=PatrolSuggestionStatus 四值（enums.yaml）；reason JSONB 记录航点/估时，
 * 下发后追加 missionId。
 */
@Entity
@Table(name = "patrol_area")
public class PatrolAreaEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: risk_area_id UUID — 关联 risk_area.id（逻辑外键） */
    @Column(name = "risk_area_id")
    private UUID riskAreaId;

    /** DDL: geometry geometry(Polygon,4326) — 覆盖区域（网格矩形） */
    @Column(name = "geometry")
    private Polygon geometry;

    /** DDL: priority INTEGER — round(risk_score) */
    @Column(name = "priority")
    private Integer priority;

    /** DDL: reason JSONB — {areaCode,riskScore,riskLevel,waypoints[],estimatedDurationMin,missionId?} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason")
    private Map<String, Object> reason;

    /** DDL: generated_at TIMESTAMPTZ NOT NULL */
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** DDL: status VARCHAR(32) — PatrolSuggestionStatus 四值 */
    @Column(name = "status")
    private String status;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRiskAreaId() { return riskAreaId; }
    public void setRiskAreaId(UUID riskAreaId) { this.riskAreaId = riskAreaId; }
    public Polygon getGeometry() { return geometry; }
    public void setGeometry(Polygon geometry) { this.geometry = geometry; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Map<String, Object> getReason() { return reason; }
    public void setReason(Map<String, Object> reason) { this.reason = reason; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

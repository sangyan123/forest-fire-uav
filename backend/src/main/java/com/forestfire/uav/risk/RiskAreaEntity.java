package com.forestfire.uav.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.MultiPolygon;

import java.time.Instant;
import java.util.UUID;

/**
 * 风险区域表实体 — 对应 db/migration/V1__baseline_schema.sql 的 risk_area（10 列）。
 * 列名 @Column(name=...) 与 DDL 逐字一致；网格自动划分（constants.yaml#risk_assessment#grid），
 * 区域按 area_code="GRID-R{row}-C{col}" 幂等 upsert。
 * NOT NULL 列（id/geometry/evaluation_time/created_at/updated_at）全部由应用赋值。
 */
@Entity
@Table(name = "risk_area")
public class RiskAreaEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: area_code VARCHAR(64) — "GRID-R{row}-C{col}"（幂等键） */
    @Column(name = "area_code")
    private String areaCode;

    /** DDL: area_name VARCHAR(128) — "网格 R{row}-C{col}" */
    @Column(name = "area_name")
    private String areaName;

    /** DDL: geometry geometry(MultiPolygon,4326) NOT NULL — 1km 网格矩形 */
    @Column(name = "geometry", nullable = false)
    private MultiPolygon geometry;

    /** DDL: risk_score DOUBLE PRECISION — 五因子加权 0~100 */
    @Column(name = "risk_score")
    private Double riskScore;

    /** DDL: risk_level VARCHAR(32) — RiskLevel 三值（enums.yaml#RiskLevel） */
    @Column(name = "risk_level")
    private String riskLevel;

    /** DDL: model_id UUID — risk-model-v1 固定 UUID */
    @Column(name = "model_id")
    private UUID modelId;

    /** DDL: evaluation_time TIMESTAMPTZ NOT NULL — 最近评估时刻 */
    @Column(name = "evaluation_time", nullable = false)
    private Instant evaluationTime;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** DDL: updated_at TIMESTAMPTZ NOT NULL */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getAreaCode() { return areaCode; }
    public void setAreaCode(String areaCode) { this.areaCode = areaCode; }
    public String getAreaName() { return areaName; }
    public void setAreaName(String areaName) { this.areaName = areaName; }
    public MultiPolygon getGeometry() { return geometry; }
    public void setGeometry(MultiPolygon geometry) { this.geometry = geometry; }
    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public Instant getEvaluationTime() { return evaluationTime; }
    public void setEvaluationTime(Instant evaluationTime) { this.evaluationTime = evaluationTime; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

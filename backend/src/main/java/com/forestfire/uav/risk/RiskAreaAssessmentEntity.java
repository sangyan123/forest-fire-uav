package com.forestfire.uav.risk;

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
 * 风险区域评估版本表实体 — 对应 risk_area_assessment（10 列），每次评估插新版本行，
 * UNIQUE (area_id, assessed_at)（同一批 assessed_at 唯一，天然满足）。
 */
@Entity
@Table(name = "risk_area_assessment")
public class RiskAreaAssessmentEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: area_id UUID NOT NULL — 关联 risk_area.id（逻辑外键） */
    @Column(name = "area_id", nullable = false)
    private UUID areaId;

    /** DDL: assessed_at TIMESTAMPTZ NOT NULL — 评估时刻（UNIQUE 组成列） */
    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    /** DDL: risk_score DOUBLE PRECISION */
    @Column(name = "risk_score")
    private Double riskScore;

    /** DDL: risk_level VARCHAR(32) */
    @Column(name = "risk_level")
    private String riskLevel;

    /** DDL: model_version VARCHAR(64) — "risk-model-v1" */
    @Column(name = "model_version")
    private String modelVersion;

    /** DDL: algorithm_config_version VARCHAR(64) — "risk-assessment-config-v1.0" */
    @Column(name = "algorithm_config_version")
    private String algorithmConfigVersion;

    /** DDL: dataset_version VARCHAR(64) — "mock-grid-5x5" */
    @Column(name = "dataset_version")
    private String datasetVersion;

    /** DDL: factors JSONB — 五因子分值快照 {HISTORICAL:xx,...} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factors")
    private Map<String, Object> factors;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAreaId() { return areaId; }
    public void setAreaId(UUID areaId) { this.areaId = areaId; }
    public Instant getAssessedAt() { return assessedAt; }
    public void setAssessedAt(Instant assessedAt) { this.assessedAt = assessedAt; }
    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getAlgorithmConfigVersion() { return algorithmConfigVersion; }
    public void setAlgorithmConfigVersion(String algorithmConfigVersion) { this.algorithmConfigVersion = algorithmConfigVersion; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public Map<String, Object> getFactors() { return factors; }
    public void setFactors(Map<String, Object> factors) { this.factors = factors; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

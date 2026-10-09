package com.forestfire.uav.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 风险因子分项表实体 — 对应 risk_feature（8 列），每次评估先删该区域旧行再插 5 行新因子
 * （HISTORICAL/WEATHER/VEGETATION/TERRAIN/HUMAN_ACTIVITY）。
 */
@Entity
@Table(name = "risk_feature")
public class RiskFeatureEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: risk_area_id UUID NOT NULL — 关联 risk_area.id（逻辑外键） */
    @Column(name = "risk_area_id", nullable = false)
    private UUID riskAreaId;

    /** DDL: feature_type VARCHAR(64) NOT NULL — 五因子类型 */
    @Column(name = "feature_type", nullable = false)
    private String featureType;

    /** DDL: feature_value DOUBLE PRECISION — 因子分值 0~100 */
    @Column(name = "feature_value")
    private Double featureValue;

    /** DDL: weight DOUBLE PRECISION — 权重（constants.yaml#risk_assessment#weights） */
    @Column(name = "weight")
    private Double weight;

    /** DDL: contribution DOUBLE PRECISION — feature_value × weight */
    @Column(name = "contribution")
    private Double contribution;

    /** DDL: source VARCHAR(128) — "ai-service mock provider" */
    @Column(name = "source")
    private String source;

    /** DDL: evaluation_time TIMESTAMPTZ NOT NULL */
    @Column(name = "evaluation_time", nullable = false)
    private Instant evaluationTime;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRiskAreaId() { return riskAreaId; }
    public void setRiskAreaId(UUID riskAreaId) { this.riskAreaId = riskAreaId; }
    public String getFeatureType() { return featureType; }
    public void setFeatureType(String featureType) { this.featureType = featureType; }
    public Double getFeatureValue() { return featureValue; }
    public void setFeatureValue(Double featureValue) { this.featureValue = featureValue; }
    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }
    public Double getContribution() { return contribution; }
    public void setContribution(Double contribution) { this.contribution = contribution; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getEvaluationTime() { return evaluationTime; }
    public void setEvaluationTime(Instant evaluationTime) { this.evaluationTime = evaluationTime; }
}

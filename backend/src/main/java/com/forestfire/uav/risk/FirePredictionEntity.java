package com.forestfire.uav.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.MultiPolygon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 火势预测表实体 — 对应 fire_prediction（11 列），F11 Level 1 规则椭圆扩散
 * （03号第91.5节），每个火情每轮预测插 30/60min 两行。
 */
@Entity
@Table(name = "fire_prediction")
public class FirePredictionEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: incident_id UUID NOT NULL — 关联 fire_incident.id（逻辑外键） */
    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    /** DDL: model_id UUID NOT NULL — fire-predict-level1 固定 UUID */
    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    /** DDL: base_time TIMESTAMPTZ NOT NULL — 预测基准时刻 */
    @Column(name = "base_time", nullable = false)
    private Instant baseTime;

    /** DDL: forecast_minutes INTEGER NOT NULL — 30/60 两档 */
    @Column(name = "forecast_minutes", nullable = false)
    private Integer forecastMinutes;

    /** DDL: predicted_geometry geometry(MultiPolygon,4326) — 扩散椭圆 */
    @Column(name = "predicted_geometry")
    private MultiPolygon predictedGeometry;

    /** DDL: predicted_area_square_meter DOUBLE PRECISION */
    @Column(name = "predicted_area_square_meter")
    private Double predictedAreaSquareMeter;

    /** DDL: confidence NUMERIC(6,5) — Level 1 基准 0.78 */
    @Column(name = "confidence")
    private BigDecimal confidence;

    /** DDL: environmental_input JSONB — 气象 mock 快照（与 DJI 遥测分离） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "environmental_input")
    private Map<String, Object> environmentalInput;

    /** DDL: prediction_result JSONB — 火头方向/长短轴/蔓延速度等过程数据 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prediction_result")
    private Map<String, Object> predictionResult;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public Instant getBaseTime() { return baseTime; }
    public void setBaseTime(Instant baseTime) { this.baseTime = baseTime; }
    public Integer getForecastMinutes() { return forecastMinutes; }
    public void setForecastMinutes(Integer forecastMinutes) { this.forecastMinutes = forecastMinutes; }
    public MultiPolygon getPredictedGeometry() { return predictedGeometry; }
    public void setPredictedGeometry(MultiPolygon predictedGeometry) { this.predictedGeometry = predictedGeometry; }
    public Double getPredictedAreaSquareMeter() { return predictedAreaSquareMeter; }
    public void setPredictedAreaSquareMeter(Double predictedAreaSquareMeter) { this.predictedAreaSquareMeter = predictedAreaSquareMeter; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Map<String, Object> getEnvironmentalInput() { return environmentalInput; }
    public void setEnvironmentalInput(Map<String, Object> environmentalInput) { this.environmentalInput = environmentalInput; }
    public Map<String, Object> getPredictionResult() { return predictionResult; }
    public void setPredictionResult(Map<String, Object> predictionResult) { this.predictionResult = predictionResult; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

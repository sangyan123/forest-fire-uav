package com.forestfire.uav.fire;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 火情核验表实体 — 对应 db/migration/V1__baseline_schema.sql 的 fire_verification（14 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                     → id（应用侧 UUID.randomUUID()）</li>
 *   <li>incident_id UUID NOT NULL      → incident_id（路径参数解析出的 fire_incident.id）</li>
 *   <li>mission_id UUID                → mission_id（请求体可选 missionId，宽容解析）</li>
 *   <li>verification_type VARCHAR(32)  → verification_type（AI 自动核验固定 "AI"）</li>
 *   <li>rgb_score NUMERIC(6,5)         → rgb_score（F04 证据分项，默认 0.9）</li>
 *   <li>thermal_score NUMERIC(6,5)     → thermal_score（默认 0.92）</li>
 *   <li>temporal_score NUMERIC(6,5)    → temporal_score（默认 0.88）</li>
 *   <li>spatial_score NUMERIC(6,5)     → spatial_score（默认 0.9）</li>
 *   <li>final_score NUMERIC(6,5)       → final_score（F04 加权总分 0.35/0.35/0.15/0.15，
 *       取 AI 返回 confidence）</li>
 *   <li>result VARCHAR(32)             → result（decision：CONFIRMED/FALSE_ALARM/UNCERTAIN）</li>
 *   <li>verifier_type VARCHAR(32)      → verifier_type（固定 "AI_SERVICE"）</li>
 *   <li>verified_at TIMESTAMPTZ        → verified_at（应用赋值 now）</li>
 *   <li>remark TEXT                    → remark（taskId/provider 等留存）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/incident_id/created_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "fire_verification")
public class FireVerificationEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: incident_id UUID NOT NULL — 关联 fire_incident.id（逻辑外键） */
    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    /** DDL: mission_id UUID — 关联 mission.id（逻辑外键，可空） */
    @Column(name = "mission_id")
    private UUID missionId;

    /** DDL: verification_type VARCHAR(32) — "AI"（F04 自动核验） */
    @Column(name = "verification_type")
    private String verificationType;

    /** DDL: rgb_score NUMERIC(6,5) — RGB 证据分项 */
    @Column(name = "rgb_score")
    private BigDecimal rgbScore;

    /** DDL: thermal_score NUMERIC(6,5) — 热红外证据分项 */
    @Column(name = "thermal_score")
    private BigDecimal thermalScore;

    /** DDL: temporal_score NUMERIC(6,5) — 时序证据分项 */
    @Column(name = "temporal_score")
    private BigDecimal temporalScore;

    /** DDL: spatial_score NUMERIC(6,5) — 空间证据分项 */
    @Column(name = "spatial_score")
    private BigDecimal spatialScore;

    /** DDL: final_score NUMERIC(6,5) — 加权总分（AI 返回 confidence） */
    @Column(name = "final_score")
    private BigDecimal finalScore;

    /** DDL: result VARCHAR(32) — CONFIRMED/FALSE_ALARM/UNCERTAIN */
    @Column(name = "result")
    private String result;

    /** DDL: verifier_type VARCHAR(32) — "AI_SERVICE" */
    @Column(name = "verifier_type")
    private String verifierType;

    /** DDL: verified_at TIMESTAMPTZ */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** DDL: remark TEXT — taskId/provider 等留存 */
    @Column(name = "remark")
    private String remark;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public UUID getMissionId() { return missionId; }
    public void setMissionId(UUID missionId) { this.missionId = missionId; }
    public String getVerificationType() { return verificationType; }
    public void setVerificationType(String verificationType) { this.verificationType = verificationType; }
    public BigDecimal getRgbScore() { return rgbScore; }
    public void setRgbScore(BigDecimal rgbScore) { this.rgbScore = rgbScore; }
    public BigDecimal getThermalScore() { return thermalScore; }
    public void setThermalScore(BigDecimal thermalScore) { this.thermalScore = thermalScore; }
    public BigDecimal getTemporalScore() { return temporalScore; }
    public void setTemporalScore(BigDecimal temporalScore) { this.temporalScore = temporalScore; }
    public BigDecimal getSpatialScore() { return spatialScore; }
    public void setSpatialScore(BigDecimal spatialScore) { this.spatialScore = spatialScore; }
    public BigDecimal getFinalScore() { return finalScore; }
    public void setFinalScore(BigDecimal finalScore) { this.finalScore = finalScore; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getVerifierType() { return verifierType; }
    public void setVerifierType(String verifierType) { this.verifierType = verifierType; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

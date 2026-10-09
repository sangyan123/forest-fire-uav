package com.forestfire.uav.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.MultiPolygon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 过火分带表实体 — 对应 db/migration/V1__baseline_schema.sql 的 assessment_area（7 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                          → id（应用侧 UUID.randomUUID()）</li>
 *   <li>report_id UUID NOT NULL             → report_id（关联 assessment_report.id，逻辑外键）</li>
 *   <li>area_type VARCHAR(64)               → area_type（BurnedAreaSeverity：SEVERE/MODERATE/LIGHT）</li>
 *   <li>geometry geometry(MultiPolygon,4326) → geometry（hibernate-spatial 映射，三分带同心收缩环）</li>
 *   <li>area_square_meter DOUBLE PRECISION   → area_square_meter（该分带面积）</li>
 *   <li>confidence NUMERIC(6,5)             → confidence（一期透传 constants.yaml assessment.confidence=0.82）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL      → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>每份报告固定 3 行分带（SEVERE/MODERATE/LIGHT），幂等覆盖时随报告一并删旧插新。</p>
 */
@Entity
@Table(name = "assessment_area")
public class AssessmentAreaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "area_type")
    private String areaType;

    @Column(name = "geometry")
    private MultiPolygon geometry;

    @Column(name = "area_square_meter")
    private Double areaSquareMeter;

    @Column(name = "confidence")
    private BigDecimal confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getReportId() { return reportId; }
    public void setReportId(UUID reportId) { this.reportId = reportId; }
    public String getAreaType() { return areaType; }
    public void setAreaType(String areaType) { this.areaType = areaType; }
    public MultiPolygon getGeometry() { return geometry; }
    public void setGeometry(MultiPolygon geometry) { this.geometry = geometry; }
    public Double getAreaSquareMeter() { return areaSquareMeter; }
    public void setAreaSquareMeter(Double areaSquareMeter) { this.areaSquareMeter = areaSquareMeter; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

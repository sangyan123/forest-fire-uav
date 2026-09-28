package com.forestfire.uav.fire;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.MultiPolygon;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 火场多边形表实体 — 对应 db/migration/V1__baseline_schema.sql 的 fire_polygon（9 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                          → id（应用侧 UUID.randomUUID()）</li>
 *   <li>incident_id UUID NOT NULL           → incident_id（关联 fire_incident.id）</li>
 *   <li>polygon geometry(MultiPolygon,4326) NOT NULL → polygon（hibernate-spatial 映射。
 *       注意 DDL 为 MultiPolygon 而非 Polygon，以 DDL 为准：AI 返回的 [lat,lon] 环
 *       先构造 Polygon 再包成 MultiPolygon 写入；无 JSONB/文本副本列（DDL 9 列中没有），
 *       GeoJSON 坐标由读取时从几何反序列化）</li>
 *   <li>area_square_meter DOUBLE PRECISION  → area_square_meter（F05 返回 areaSquareMeters）</li>
 *   <li>perimeter_meter DOUBLE PRECISION    → perimeter_meter（应用按环逐边 Haversine 累加，
 *       AI 不返回周长）</li>
 *   <li>confidence NUMERIC(6,5)             → confidence（F05 mock 不返回，可空不赋值）</li>
 *   <li>source_media_id UUID                → source_media_id（分析管线无媒体输入，可空不赋值）</li>
 *   <li>detected_at TIMESTAMPTZ NOT NULL    → detected_at（分析时刻，应用赋值）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL     → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/incident_id/polygon/detected_at/created_at）全部由应用赋值。
 * 无 radius 列：查询侧由面积派生等效半径 r=√(A/π)。</p>
 */
@Entity
@Table(name = "fire_polygon")
public class FirePolygonEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: incident_id UUID NOT NULL — 关联 fire_incident.id（逻辑外键） */
    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    /** DDL: polygon geometry(MultiPolygon,4326) NOT NULL — hibernate-spatial 映射（MultiPolygon 以 DDL 为准） */
    @Column(name = "polygon", nullable = false)
    private MultiPolygon polygon;

    /** DDL: area_square_meter DOUBLE PRECISION — F05 面积（平方米） */
    @Column(name = "area_square_meter")
    private Double areaSquareMeter;

    /** DDL: perimeter_meter DOUBLE PRECISION — 周长（米，应用 Haversine 累加） */
    @Column(name = "perimeter_meter")
    private Double perimeterMeter;

    /** DDL: confidence NUMERIC(6,5)（F05 mock 不返回，可空） */
    @Column(name = "confidence")
    private BigDecimal confidence;

    /** DDL: source_media_id UUID（分析管线无媒体输入，可空） */
    @Column(name = "source_media_id")
    private UUID sourceMediaId;

    /** DDL: detected_at TIMESTAMPTZ NOT NULL — 分析时刻 */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public MultiPolygon getPolygon() { return polygon; }
    public void setPolygon(MultiPolygon polygon) { this.polygon = polygon; }
    public Double getAreaSquareMeter() { return areaSquareMeter; }
    public void setAreaSquareMeter(Double areaSquareMeter) { this.areaSquareMeter = areaSquareMeter; }
    public Double getPerimeterMeter() { return perimeterMeter; }
    public void setPerimeterMeter(Double perimeterMeter) { this.perimeterMeter = perimeterMeter; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public UUID getSourceMediaId() { return sourceMediaId; }
    public void setSourceMediaId(UUID sourceMediaId) { this.sourceMediaId = sourceMediaId; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

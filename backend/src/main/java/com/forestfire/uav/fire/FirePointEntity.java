package com.forestfire.uav.fire;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 火点表实体 — 对应 db/migration/V1__baseline_schema.sql 的 fire_point（13 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                          → id（应用侧 UUID.randomUUID()）</li>
 *   <li>incident_id UUID                    → incident_id（去重命中既有 incident 则关联，
 *       否则新建 incident 后回填；任务书口语中的 "fire_incident.fire_point_id" 实际是本列，
 *       DDL fire_incident 无 fire_point_id 列，以 DDL 为准）</li>
 *   <li>detection_id UUID                   → detection_id（来源 fire_detection.id）</li>
 *   <li>latitude DOUBLE PRECISION NOT NULL  → latitude（F03 定位结果）</li>
 *   <li>longitude DOUBLE PRECISION NOT NULL → longitude（F03 定位结果）</li>
 *   <li>altitude DOUBLE PRECISION           → altitude（media.position.altitude）</li>
 *   <li>geometry geometry(Point,4326) NOT NULL → geometry（hibernate-spatial 映射，
 *       由 lat/lon 构造 SRID 4326 Point，NOT NULL 覆盖）</li>
 *   <li>position_error_radius DOUBLE PRECISION → position_error_radius（F03 accuracy，米）</li>
 *   <li>location_method VARCHAR(32) NOT NULL → location_method（F03 返回 method，
 *       LocationMethod 五值枚举；AI 未返回时兜底 "RAY_GROUND_INTERSECTION"
 *       （Phase 1 主用，enums.yaml#LocationMethod））</li>
 *   <li>confidence NUMERIC(6,5)             → confidence（来源检测置信度）</li>
 *   <li>source_uav_id UUID                  → source_uav_id（device.id）</li>
 *   <li>detected_at TIMESTAMPTZ NOT NULL    → detected_at（与 detection_time 同源：
 *       media.capturedAt，兜底 now，NOT NULL 覆盖）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL     → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/latitude/longitude/geometry/location_method/detected_at/created_at）
 * 全部由应用赋值。</p>
 */
@Entity
@Table(name = "fire_point")
public class FirePointEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: incident_id UUID — 关联 fire_incident.id（逻辑外键；去重后回填） */
    @Column(name = "incident_id")
    private UUID incidentId;

    /** DDL: detection_id UUID — 关联 fire_detection.id（逻辑外键） */
    @Column(name = "detection_id")
    private UUID detectionId;

    /** DDL: latitude DOUBLE PRECISION NOT NULL */
    @Column(name = "latitude", nullable = false)
    private Double latitude;

    /** DDL: longitude DOUBLE PRECISION NOT NULL */
    @Column(name = "longitude", nullable = false)
    private Double longitude;

    /** DDL: altitude DOUBLE PRECISION */
    @Column(name = "altitude")
    private Double altitude;

    /** DDL: geometry geometry(Point,4326) NOT NULL — hibernate-spatial 映射，应用由 lat/lon 构造 */
    @Column(name = "geometry", nullable = false)
    private Point geometry;

    /** DDL: position_error_radius DOUBLE PRECISION — F03 accuracy（米） */
    @Column(name = "position_error_radius")
    private Double positionErrorRadius;

    /** DDL: location_method VARCHAR(32) NOT NULL — LocationMethod 五值枚举 */
    @Column(name = "location_method", nullable = false)
    private String locationMethod;

    /** DDL: confidence NUMERIC(6,5) */
    @Column(name = "confidence")
    private BigDecimal confidence;

    /** DDL: source_uav_id UUID — 关联 uav_device.id（逻辑外键） */
    @Column(name = "source_uav_id")
    private UUID sourceUavId;

    /** DDL: detected_at TIMESTAMPTZ NOT NULL */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public UUID getDetectionId() { return detectionId; }
    public void setDetectionId(UUID detectionId) { this.detectionId = detectionId; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getAltitude() { return altitude; }
    public void setAltitude(Double altitude) { this.altitude = altitude; }
    public Point getGeometry() { return geometry; }
    public void setGeometry(Point geometry) { this.geometry = geometry; }
    public Double getPositionErrorRadius() { return positionErrorRadius; }
    public void setPositionErrorRadius(Double positionErrorRadius) { this.positionErrorRadius = positionErrorRadius; }
    public String getLocationMethod() { return locationMethod; }
    public void setLocationMethod(String locationMethod) { this.locationMethod = locationMethod; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public UUID getSourceUavId() { return sourceUavId; }
    public void setSourceUavId(UUID sourceUavId) { this.sourceUavId = sourceUavId; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

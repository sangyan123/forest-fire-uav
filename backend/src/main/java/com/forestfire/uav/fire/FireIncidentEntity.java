package com.forestfire.uav.fire;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 火情事件表实体 — 对应 db/migration/V1__baseline_schema.sql 的 fire_incident（22 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                          → id（应用侧 UUID.randomUUID()）</li>
 *   <li>incident_no VARCHAR(64) NOT NULL UNIQUE → incident_no（"INC-"+yyyyMMdd(UTC)+"-"+4位序号，
 *       序号=当日前缀计数+1，单网关 demo 场景碰撞概率可忽略）</li>
 *   <li>title VARCHAR(255)                  → title（自动创建固定 "无人机巡检发现疑似火情"）</li>
 *   <li>status VARCHAR(32) NOT NULL         → status（IncidentStatus 8 值；创建 SUSPECTED）</li>
 *   <li>level VARCHAR(32)                   → level（任务书口语中的 priority 落本列——DDL
 *       fire_incident 无 priority 列；confidence≥0.80→HIGH 否则 MEDIUM）</li>
 *   <li>latitude DOUBLE PRECISION           → latitude（最新火点坐标）</li>
 *   <li>longitude DOUBLE PRECISION          → longitude（最新火点坐标）</li>
 *   <li>geometry geometry(Point,4326)       → geometry（hibernate-spatial 映射，可空但有值即写入）</li>
 *   <li>first_detected_at TIMESTAMPTZ       → first_detected_at（任务书口语中的
 *       first_detection_at；DDL 列名为 first_detected_at，以 DDL 为准；去重合并时保留最早值）</li>
 *   <li>confirmed_at TIMESTAMPTZ            → confirmed_at（迁移 CONFIRMED 时赋值）</li>
 *   <li>resolved_at TIMESTAMPTZ             → resolved_at（迁移 RESOLVED 时赋值）</li>
 *   <li>current_area_square_meter DOUBLE PRECISION → current_area_square_meter（F05 未接入，可空）</li>
 *   <li>max_area_square_meter DOUBLE PRECISION     → max_area_square_meter（F05 未接入，可空）</li>
 *   <li>current_temperature DOUBLE PRECISION       → current_temperature（热成像未接入，可空）</li>
 *   <li>source_uav_id UUID                  → source_uav_id（首个检测来源 device.id）</li>
 *   <li>source_detection_id UUID            → source_detection_id（首个 fire_detection.id）</li>
 *   <li>verification_status VARCHAR(32)     → verification_status（F04 结论：
 *       CONFIRMED/FALSE_ALARM/UNCERTAIN）</li>
 *   <li>false_alarm BOOLEAN                 → false_alarm（FALSE_ALARM 时 true；DDL 默认 FALSE，
 *       应用显式赋 FALSE 起步）</li>
 *   <li>description TEXT                    → description（MVP 不赋值，可空）</li>
 *   <li>extra JSONB                         → extra（Map：latestConfidence/maxConfidence/
 *       detectionCount/lastStatusReason 等宽松留存）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL     → created_at（应用赋值，NOT NULL 覆盖）</li>
 *   <li>updated_at TIMESTAMPTZ NOT NULL     → updated_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/incident_no/status/created_at/updated_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "fire_incident")
public class FireIncidentEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: incident_no VARCHAR(64) NOT NULL UNIQUE — "INC-"+日期+4位序号 */
    @Column(name = "incident_no", nullable = false)
    private String incidentNo;

    /** DDL: title VARCHAR(255) */
    @Column(name = "title")
    private String title;

    /** DDL: status VARCHAR(32) NOT NULL — IncidentStatus 8 值 */
    @Column(name = "status", nullable = false)
    private String status;

    /** DDL: level VARCHAR(32) — 严重级别（任务书口语 priority 落本列） */
    @Column(name = "level")
    private String level;

    /** DDL: latitude DOUBLE PRECISION — 最新火点坐标 */
    @Column(name = "latitude")
    private Double latitude;

    /** DDL: longitude DOUBLE PRECISION — 最新火点坐标 */
    @Column(name = "longitude")
    private Double longitude;

    /** DDL: geometry geometry(Point,4326) — hibernate-spatial 映射（可空） */
    @Column(name = "geometry")
    private Point geometry;

    /** DDL: first_detected_at TIMESTAMPTZ — 首次检测时间（合并时保留最早值） */
    @Column(name = "first_detected_at")
    private Instant firstDetectedAt;

    /** DDL: confirmed_at TIMESTAMPTZ — 迁移 CONFIRMED 时赋值 */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** DDL: resolved_at TIMESTAMPTZ — 迁移 RESOLVED 时赋值 */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** DDL: current_area_square_meter DOUBLE PRECISION（F05 未接入，可空） */
    @Column(name = "current_area_square_meter")
    private Double currentAreaSquareMeter;

    /** DDL: max_area_square_meter DOUBLE PRECISION（F05 未接入，可空） */
    @Column(name = "max_area_square_meter")
    private Double maxAreaSquareMeter;

    /** DDL: current_temperature DOUBLE PRECISION（热成像未接入，可空） */
    @Column(name = "current_temperature")
    private Double currentTemperature;

    /** DDL: source_uav_id UUID — 首个检测来源 uav_device.id（逻辑外键） */
    @Column(name = "source_uav_id")
    private UUID sourceUavId;

    /** DDL: source_detection_id UUID — 首个 fire_detection.id（逻辑外键） */
    @Column(name = "source_detection_id")
    private UUID sourceDetectionId;

    /** DDL: verification_status VARCHAR(32) — F04 结论 */
    @Column(name = "verification_status")
    private String verificationStatus;

    /** DDL: false_alarm BOOLEAN — FALSE_ALARM 时 true */
    @Column(name = "false_alarm")
    private Boolean falseAlarm;

    /** DDL: description TEXT（MVP 不赋值，可空） */
    @Column(name = "description")
    private String description;

    /** DDL: extra JSONB — latestConfidence/maxConfidence/detectionCount/lastStatusReason 等 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra")
    private Map<String, Object> extra;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** DDL: updated_at TIMESTAMPTZ NOT NULL */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getIncidentNo() { return incidentNo; }
    public void setIncidentNo(String incidentNo) { this.incidentNo = incidentNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Point getGeometry() { return geometry; }
    public void setGeometry(Point geometry) { this.geometry = geometry; }
    public Instant getFirstDetectedAt() { return firstDetectedAt; }
    public void setFirstDetectedAt(Instant firstDetectedAt) { this.firstDetectedAt = firstDetectedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Double getCurrentAreaSquareMeter() { return currentAreaSquareMeter; }
    public void setCurrentAreaSquareMeter(Double currentAreaSquareMeter) { this.currentAreaSquareMeter = currentAreaSquareMeter; }
    public Double getMaxAreaSquareMeter() { return maxAreaSquareMeter; }
    public void setMaxAreaSquareMeter(Double maxAreaSquareMeter) { this.maxAreaSquareMeter = maxAreaSquareMeter; }
    public Double getCurrentTemperature() { return currentTemperature; }
    public void setCurrentTemperature(Double currentTemperature) { this.currentTemperature = currentTemperature; }
    public UUID getSourceUavId() { return sourceUavId; }
    public void setSourceUavId(UUID sourceUavId) { this.sourceUavId = sourceUavId; }
    public UUID getSourceDetectionId() { return sourceDetectionId; }
    public void setSourceDetectionId(UUID sourceDetectionId) { this.sourceDetectionId = sourceDetectionId; }
    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
    public Boolean getFalseAlarm() { return falseAlarm; }
    public void setFalseAlarm(Boolean falseAlarm) { this.falseAlarm = falseAlarm; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

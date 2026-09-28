package com.forestfire.uav.fire;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 火情检测表实体 — 对应 db/migration/V1__baseline_schema.sql 的 fire_detection（14 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                        → id（应用侧 UUID.randomUUID()）</li>
 *   <li>algorithm_result_id UUID          → algorithm_result_id（MVP 无 algorithm_task/result
 *       落库链路，可空不赋值）</li>
 *   <li>uav_id UUID                       → uav_id（device.id，按 device_code 查得；可空）</li>
 *   <li>media_id UUID                     → media_id（media_file.id；协议 mediaId 为字符串编码，
 *       经 media_file 落库后引用其 UUID 主键）</li>
 *   <li>detection_type VARCHAR(32) NOT NULL → detection_type（自动闭环 "SMOKE"；手动接口用请求值）</li>
 *   <li>confidence NUMERIC(6,5)           → confidence（BigDecimal）</li>
 *   <li>bbox JSONB                        → bbox（Map：{x,y,width,height}；AI 返回 [x,y,w,h]
 *       数组归一为对象存储。任务书"bbox 四列"与 DDL 不符——DDL 仅单 JSONB 列，以 DDL 为准）</li>
 *   <li>latitude DOUBLE PRECISION         → latitude（F03 定位结果回填）</li>
 *   <li>longitude DOUBLE PRECISION        → longitude（F03 定位结果回填）</li>
 *   <li>geometry geometry(Point,4326)     → geometry（hibernate-spatial 映射，可空但有值即写入）</li>
 *   <li>detection_time TIMESTAMPTZ NOT NULL → detection_time（media.capturedAt，缺失兜底 now；
 *       注意 DDL 列名为 detection_time 而非任务书口语中的 detected_at，以 DDL 为准）</li>
 *   <li>model_id UUID                     → model_id（MVP mock 无模型注册，可空不赋值）</li>
 *   <li>temporal_confirmed BOOLEAN        → temporal_confirmed（MVP 显式赋 FALSE，
 *       F01 时序稳定性 3/5 帧确认留待后续）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL   → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/detection_type/detection_time/created_at）全部由应用赋值。
 * 与 incident 的关联经 fire_point.detection_id → fire_point.incident_id 传递（本表无 incident_id 列）。</p>
 */
@Entity
@Table(name = "fire_detection")
public class FireDetectionEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: algorithm_result_id UUID（MVP 不赋值，可空） */
    @Column(name = "algorithm_result_id")
    private UUID algorithmResultId;

    /** DDL: uav_id UUID — 关联 uav_device.id（逻辑外键） */
    @Column(name = "uav_id")
    private UUID uavId;

    /** DDL: media_id UUID — 关联 media_file.id（逻辑外键） */
    @Column(name = "media_id")
    private UUID mediaId;

    /** DDL: detection_type VARCHAR(32) NOT NULL — 检测目标类别（SMOKE/FIRE...） */
    @Column(name = "detection_type", nullable = false)
    private String detectionType;

    /** DDL: confidence NUMERIC(6,5) */
    @Column(name = "confidence")
    private BigDecimal confidence;

    /** DDL: bbox JSONB — 归一为 {x,y,width,height} 对象（任务书"bbox 四列"以 DDL 单 JSONB 列为准） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bbox")
    private Map<String, Object> bbox;

    /** DDL: latitude DOUBLE PRECISION — F03 定位结果回填 */
    @Column(name = "latitude")
    private Double latitude;

    /** DDL: longitude DOUBLE PRECISION — F03 定位结果回填 */
    @Column(name = "longitude")
    private Double longitude;

    /** DDL: geometry geometry(Point,4326) — hibernate-spatial 映射（可空） */
    @Column(name = "geometry")
    private Point geometry;

    /** DDL: detection_time TIMESTAMPTZ NOT NULL — media.capturedAt（兜底 now） */
    @Column(name = "detection_time", nullable = false)
    private Instant detectionTime;

    /** DDL: model_id UUID（MVP mock 无模型注册，可空） */
    @Column(name = "model_id")
    private UUID modelId;

    /** DDL: temporal_confirmed BOOLEAN — MVP 显式赋 FALSE */
    @Column(name = "temporal_confirmed")
    private Boolean temporalConfirmed;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAlgorithmResultId() { return algorithmResultId; }
    public void setAlgorithmResultId(UUID algorithmResultId) { this.algorithmResultId = algorithmResultId; }
    public UUID getUavId() { return uavId; }
    public void setUavId(UUID uavId) { this.uavId = uavId; }
    public UUID getMediaId() { return mediaId; }
    public void setMediaId(UUID mediaId) { this.mediaId = mediaId; }
    public String getDetectionType() { return detectionType; }
    public void setDetectionType(String detectionType) { this.detectionType = detectionType; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public Map<String, Object> getBbox() { return bbox; }
    public void setBbox(Map<String, Object> bbox) { this.bbox = bbox; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Point getGeometry() { return geometry; }
    public void setGeometry(Point geometry) { this.geometry = geometry; }
    public Instant getDetectionTime() { return detectionTime; }
    public void setDetectionTime(Instant detectionTime) { this.detectionTime = detectionTime; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public Boolean getTemporalConfirmed() { return temporalConfirmed; }
    public void setTemporalConfirmed(Boolean temporalConfirmed) { this.temporalConfirmed = temporalConfirmed; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

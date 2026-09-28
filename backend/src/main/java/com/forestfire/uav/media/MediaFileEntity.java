package com.forestfire.uav.media;

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
 * 媒体文件表实体 — 对应 db/migration/V1__baseline_schema.sql 的 media_file（21 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                       → id（应用侧 UUID.randomUUID()）</li>
 *   <li>uav_id UUID                      → uav_id（device.id，按 device_code 查得；可空）</li>
 *   <li>mission_id UUID                  → mission_id（媒体消息不携带任务，可空不赋值）</li>
 *   <li>media_type VARCHAR(32) NOT NULL  → media_type（media.type：RGB_IMAGE/THERMAL_IMAGE...）</li>
 *   <li>mime_type VARCHAR(128)           → mime_type（media.format 映射：JPEG→image/jpeg 等）</li>
 *   <li>storage_provider VARCHAR(32)     → storage_provider（url 前缀派生：s3://→S3）</li>
 *   <li>bucket_name VARCHAR(128)         → bucket_name（s3://bucket/key 解析）</li>
 *   <li>object_key TEXT NOT NULL         → object_key（s3://bucket/key 解析 key；url 缺失时
 *       兜底 "media/{protocolMediaId}"，NOT NULL 覆盖）</li>
 *   <li>file_size BIGINT                 → file_size（media.size）</li>
 *   <li>checksum VARCHAR(128)            → checksum（消息不携带，可空不赋值）</li>
 *   <li>captured_at TIMESTAMPTZ          → captured_at（media.capturedAt）</li>
 *   <li>latitude DOUBLE PRECISION        → latitude（media.position.latitude）</li>
 *   <li>longitude DOUBLE PRECISION       → longitude（media.position.longitude）</li>
 *   <li>geometry geometry(Point,4326)    → geometry（PostGIS 列，hibernate-spatial 映射，可空但有值即写入）</li>
 *   <li>width INTEGER                    → width（media.width）</li>
 *   <li>height INTEGER                   → height（media.height）</li>
 *   <li>frame_rate DOUBLE PRECISION      → frame_rate（视频才有，图片消息可空不赋值）</li>
 *   <li>metadata JSONB                   → metadata（Map：protocolMediaId/messageId/messageType/
 *       source/sequence/timestamp/format/camera/gimbal/thermal 等宽松留存）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL  → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/media_type/object_key/created_at）全部由应用赋值。</p>
 *
 * <p>协议 mediaId（如 "MEDIA-0001"）为字符串编码、非 UUID，故落 metadata.protocolMediaId；
 * fire_detection.media_id 引用本表 UUID 主键（逻辑外键）。</p>
 */
@Entity
@Table(name = "media_file")
public class MediaFileEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: uav_id UUID — 关联 uav_device.id（逻辑外键） */
    @Column(name = "uav_id")
    private UUID uavId;

    /** DDL: mission_id UUID（媒体消息不携带任务，MVP 不赋值） */
    @Column(name = "mission_id")
    private UUID missionId;

    /** DDL: media_type VARCHAR(32) NOT NULL — media.type 七值枚举（第一阶段 RGB_IMAGE/THERMAL_IMAGE） */
    @Column(name = "media_type", nullable = false)
    private String mediaType;

    /** DDL: mime_type VARCHAR(128) — 由 media.format 映射 */
    @Column(name = "mime_type")
    private String mimeType;

    /** DDL: storage_provider VARCHAR(32) — 由 url 前缀派生 */
    @Column(name = "storage_provider")
    private String storageProvider;

    /** DDL: bucket_name VARCHAR(128) — 由 url 解析 */
    @Column(name = "bucket_name")
    private String bucketName;

    /** DDL: object_key TEXT NOT NULL — 由 url 解析（兜底 media/{protocolMediaId}） */
    @Column(name = "object_key", nullable = false)
    private String objectKey;

    /** DDL: file_size BIGINT — media.size */
    @Column(name = "file_size")
    private Long fileSize;

    /** DDL: checksum VARCHAR(128)（消息不携带，可空） */
    @Column(name = "checksum")
    private String checksum;

    /** DDL: captured_at TIMESTAMPTZ — media.capturedAt */
    @Column(name = "captured_at")
    private Instant capturedAt;

    /** DDL: latitude DOUBLE PRECISION — media.position.latitude */
    @Column(name = "latitude")
    private Double latitude;

    /** DDL: longitude DOUBLE PRECISION — media.position.longitude */
    @Column(name = "longitude")
    private Double longitude;

    /** DDL: geometry geometry(Point,4326) — hibernate-spatial 映射（可空） */
    @Column(name = "geometry")
    private Point geometry;

    /** DDL: width INTEGER — media.width */
    @Column(name = "width")
    private Integer width;

    /** DDL: height INTEGER — media.height */
    @Column(name = "height")
    private Integer height;

    /** DDL: frame_rate DOUBLE PRECISION（视频才有，可空） */
    @Column(name = "frame_rate")
    private Double frameRate;

    /** DDL: metadata JSONB — 协议 mediaId 与消息头等宽松留存 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUavId() { return uavId; }
    public void setUavId(UUID uavId) { this.uavId = uavId; }
    public UUID getMissionId() { return missionId; }
    public void setMissionId(UUID missionId) { this.missionId = missionId; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getStorageProvider() { return storageProvider; }
    public void setStorageProvider(String storageProvider) { this.storageProvider = storageProvider; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Point getGeometry() { return geometry; }
    public void setGeometry(Point geometry) { this.geometry = geometry; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Double getFrameRate() { return frameRate; }
    public void setFrameRate(Double frameRate) { this.frameRate = frameRate; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

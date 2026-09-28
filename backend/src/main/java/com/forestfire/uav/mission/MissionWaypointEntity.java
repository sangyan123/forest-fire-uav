package com.forestfire.uav.mission;

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
 * 任务航点表实体 — 对应 db/migration/V1__baseline_schema.sql 的 mission_waypoint（11 列）。
 *
 * <p>MVP 每个任务创建 1 个航点（sequence_no=1，目标点）；
 * altitude/altitudeMode/requiredCapabilities 存 action JSONB。</p>
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                          → id（应用侧 UUID.randomUUID()）</li>
 *   <li>mission_id UUID NOT NULL            → mission_id（关联 mission.id）</li>
 *   <li>sequence_no INTEGER NOT NULL        → sequence_no（MVP 固定 1，UNIQUE(mission_id,sequence_no)）</li>
 *   <li>latitude DOUBLE PRECISION NOT NULL  → latitude（target.latitude）</li>
 *   <li>longitude DOUBLE PRECISION NOT NULL → longitude（target.longitude）</li>
 *   <li>geometry geometry(Point,4326)       → geometry（hibernate-spatial 映射，可空但有值即写入）</li>
 *   <li>altitude DOUBLE PRECISION           → altitude（target.altitude，缺省 100.0）</li>
 *   <li>speed DOUBLE PRECISION              → speed（MVP 不赋值，可空）</li>
 *   <li>heading DOUBLE PRECISION            → heading（MVP 不赋值，可空）</li>
 *   <li>action JSONB                        → action（Map：altitudeMode（缺省 RELATIVE_TO_TAKEOFF）、
 *       requiredCapabilities、altitude 冗余留存）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL     → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/mission_id/sequence_no/latitude/longitude/created_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "mission_waypoint")
public class MissionWaypointEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: mission_id UUID NOT NULL — 关联 mission.id（逻辑外键） */
    @Column(name = "mission_id", nullable = false)
    private UUID missionId;

    /** DDL: sequence_no INTEGER NOT NULL — MVP 固定 1 */
    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    /** DDL: latitude DOUBLE PRECISION NOT NULL */
    @Column(name = "latitude", nullable = false)
    private Double latitude;

    /** DDL: longitude DOUBLE PRECISION NOT NULL */
    @Column(name = "longitude", nullable = false)
    private Double longitude;

    /** DDL: geometry geometry(Point,4326) — hibernate-spatial 映射（可空） */
    @Column(name = "geometry")
    private Point geometry;

    /** DDL: altitude DOUBLE PRECISION */
    @Column(name = "altitude")
    private Double altitude;

    /** DDL: speed DOUBLE PRECISION（MVP 不赋值，可空） */
    @Column(name = "speed")
    private Double speed;

    /** DDL: heading DOUBLE PRECISION（MVP 不赋值，可空） */
    @Column(name = "heading")
    private Double heading;

    /** DDL: action JSONB — altitudeMode/requiredCapabilities 等 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action")
    private Map<String, Object> action;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getMissionId() { return missionId; }
    public void setMissionId(UUID missionId) { this.missionId = missionId; }
    public Integer getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Point getGeometry() { return geometry; }
    public void setGeometry(Point geometry) { this.geometry = geometry; }
    public Double getAltitude() { return altitude; }
    public void setAltitude(Double altitude) { this.altitude = altitude; }
    public Double getSpeed() { return speed; }
    public void setSpeed(Double speed) { this.speed = speed; }
    public Double getHeading() { return heading; }
    public void setHeading(Double heading) { this.heading = heading; }
    public Map<String, Object> getAction() { return action; }
    public void setAction(Map<String, Object> action) { this.action = action; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.forestfire.uav.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 遥测表实体 — 对应 db/migration/V1__baseline_schema.sql 的 uav_telemetry。
 *
 * <p>【DDL 逐列核对记录】DDL 实际共 26 列（任务书"27 列"与 DDL 不符，以 DDL 为准；
 * 可能将复合主键 (id, event_time) 计为两列之外的项）：列名 @Column(name=...) 与 DDL 逐字一致。</p>
 * <ol>
 *   <li>id BIGSERIAL, 复合主键 (id, event_time) → id（Long + IDENTITY，由 DB 序列生成；
 *       注意：任务书原文写 "id=UUID.randomUUID()"，但 DDL 该列为 BIGSERIAL 非 UUID，
 *       依据"列名/结构必须逐字一致"原则遵循 DDL，此为任务书与 DDL 的冲突点，按 DDL 处理）</li>
 *   <li>uav_id UUID NOT NULL           → uav_id（device.id，按 device_code 查得）</li>
 *   <li>event_time TIMESTAMPTZ NOT NULL → event_time（message.timestamp，NOT NULL 覆盖）</li>
 *   <li>latitude DOUBLE PRECISION      → latitude（position.latitude）</li>
 *   <li>longitude DOUBLE PRECISION     → longitude（position.longitude）</li>
 *   <li>geometry geometry(Point,4326)  → 【不映射】PostGIS 空间列，可空；避免引入
 *       hibernate-spatial 依赖，INSERT 不含该列时 DB 填 NULL</li>
 *   <li>height DOUBLE PRECISION        → height（position.absoluteAltitude）</li>
 *   <li>relative_height DOUBLE PRECISION → relative_height（position.relativeAltitude）</li>
 *   <li>horizontal_speed DOUBLE PRECISION → horizontal_speed（velocity.horizontalSpeed）</li>
 *   <li>vertical_speed DOUBLE PRECISION  → vertical_speed（velocity.verticalSpeed）</li>
 *   <li>heading DOUBLE PRECISION       → heading（attitude.heading）</li>
 *   <li>pitch DOUBLE PRECISION         → pitch（attitude.pitch）</li>
 *   <li>roll DOUBLE PRECISION          → roll（attitude.roll）</li>
 *   <li>gps_satellites INTEGER         → gps_satellites（navigation.gpsSatellites）</li>
 *   <li>rtk_satellites INTEGER         → rtk_satellites（navigation.rtkSatellites）</li>
 *   <li>rtk_status VARCHAR(32)         → rtk_status（navigation.rtkStatus）</li>
 *   <li>battery_percent NUMERIC(5,2)   → battery_percent（battery.percentage）</li>
 *   <li>remaining_flight_time INTEGER  → remaining_flight_time（battery.remainingFlightTime）</li>
 *   <li>gimbal_pitch DOUBLE PRECISION  → gimbal_pitch（gimbal.pitch）</li>
 *   <li>gimbal_roll DOUBLE PRECISION   → gimbal_roll（gimbal.roll）</li>
 *   <li>gimbal_yaw DOUBLE PRECISION    → gimbal_yaw（gimbal.yaw）</li>
 *   <li>flight_mode VARCHAR(64)        → flight_mode（flight.mode）</li>
 *   <li>mission_id UUID                → mission_id（flight.missionId，宽容解析：非 UUID 字面量
 *       如 "MISSION-001" 时置 NULL，避免 NOT NULL 之外的脏数据报错）</li>
 *   <li>communication_status VARCHAR(32) → communication_status（link.connected 派生：
 *       true→"CONNECTED"，false/缺失→"DISCONNECTED"）</li>
 *   <li>raw_data_id UUID               → raw_data_id（MVP 无 raw_uav_data 落库链路，可空不赋值）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL → created_at（应用赋值 Instant.now()，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id 由序列生成 / uav_id / event_time / created_at）全部有值；
 * 26 列中 25 列映射、1 个 PostGIS geometry 列有意不映射（可空）。</p>
 *
 * <p>时间列映射：java.time.Instant 走 Hibernate 6 默认 TIMESTAMP_UTC（PostgreSQL 上即
 * timestamptz，UTC 绑定），无需额外注解；ddl-auto=none 不做 schema 校验。</p>
 */
@Entity
@Table(name = "uav_telemetry")
public class UavTelemetryEntity {

    /**
     * DDL: id BIGSERIAL（复合主键 (id, event_time) 之一）。
     * JPA 侧仅以 id 作为 @Id，由 DB 序列生成（IDENTITY）；
     * event_time 由应用赋值，联合主键约束天然满足。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** DDL: uav_id UUID NOT NULL — 关联 uav_device.id（逻辑外键） */
    @Column(name = "uav_id", nullable = false)
    private UUID uavId;

    /** DDL: event_time TIMESTAMPTZ NOT NULL — message.timestamp */
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    /** DDL: latitude DOUBLE PRECISION ← position.latitude */
    @Column(name = "latitude")
    private Double latitude;

    /** DDL: longitude DOUBLE PRECISION ← position.longitude */
    @Column(name = "longitude")
    private Double longitude;

    /** DDL: height DOUBLE PRECISION ← position.absoluteAltitude */
    @Column(name = "height")
    private Double height;

    /** DDL: relative_height DOUBLE PRECISION ← position.relativeAltitude */
    @Column(name = "relative_height")
    private Double relativeHeight;

    /** DDL: horizontal_speed DOUBLE PRECISION ← velocity.horizontalSpeed */
    @Column(name = "horizontal_speed")
    private Double horizontalSpeed;

    /** DDL: vertical_speed DOUBLE PRECISION ← velocity.verticalSpeed */
    @Column(name = "vertical_speed")
    private Double verticalSpeed;

    /** DDL: heading DOUBLE PRECISION ← attitude.heading */
    @Column(name = "heading")
    private Double heading;

    /** DDL: pitch DOUBLE PRECISION ← attitude.pitch */
    @Column(name = "pitch")
    private Double pitch;

    /** DDL: roll DOUBLE PRECISION ← attitude.roll */
    @Column(name = "roll")
    private Double roll;

    /** DDL: gps_satellites INTEGER ← navigation.gpsSatellites */
    @Column(name = "gps_satellites")
    private Integer gpsSatellites;

    /** DDL: rtk_satellites INTEGER ← navigation.rtkSatellites */
    @Column(name = "rtk_satellites")
    private Integer rtkSatellites;

    /** DDL: rtk_status VARCHAR(32) ← navigation.rtkStatus */
    @Column(name = "rtk_status")
    private String rtkStatus;

    /** DDL: battery_percent NUMERIC(5,2) ← battery.percentage */
    @Column(name = "battery_percent")
    private BigDecimal batteryPercent;

    /** DDL: remaining_flight_time INTEGER ← battery.remainingFlightTime */
    @Column(name = "remaining_flight_time")
    private Integer remainingFlightTime;

    /** DDL: gimbal_pitch DOUBLE PRECISION ← gimbal.pitch */
    @Column(name = "gimbal_pitch")
    private Double gimbalPitch;

    /** DDL: gimbal_roll DOUBLE PRECISION ← gimbal.roll */
    @Column(name = "gimbal_roll")
    private Double gimbalRoll;

    /** DDL: gimbal_yaw DOUBLE PRECISION ← gimbal.yaw */
    @Column(name = "gimbal_yaw")
    private Double gimbalYaw;

    /** DDL: flight_mode VARCHAR(64) ← flight.mode */
    @Column(name = "flight_mode")
    private String flightMode;

    /** DDL: mission_id UUID ← flight.missionId（宽容解析） */
    @Column(name = "mission_id")
    private UUID missionId;

    /** DDL: communication_status VARCHAR(32) ← link.connected 派生 CONNECTED/DISCONNECTED */
    @Column(name = "communication_status")
    private String communicationStatus;

    /** DDL: raw_data_id UUID — MVP 不赋值（可空） */
    @Column(name = "raw_data_id")
    private UUID rawDataId;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getUavId() { return uavId; }
    public void setUavId(UUID uavId) { this.uavId = uavId; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getHeight() { return height; }
    public void setHeight(Double height) { this.height = height; }
    public Double getRelativeHeight() { return relativeHeight; }
    public void setRelativeHeight(Double relativeHeight) { this.relativeHeight = relativeHeight; }
    public Double getHorizontalSpeed() { return horizontalSpeed; }
    public void setHorizontalSpeed(Double horizontalSpeed) { this.horizontalSpeed = horizontalSpeed; }
    public Double getVerticalSpeed() { return verticalSpeed; }
    public void setVerticalSpeed(Double verticalSpeed) { this.verticalSpeed = verticalSpeed; }
    public Double getHeading() { return heading; }
    public void setHeading(Double heading) { this.heading = heading; }
    public Double getPitch() { return pitch; }
    public void setPitch(Double pitch) { this.pitch = pitch; }
    public Double getRoll() { return roll; }
    public void setRoll(Double roll) { this.roll = roll; }
    public Integer getGpsSatellites() { return gpsSatellites; }
    public void setGpsSatellites(Integer gpsSatellites) { this.gpsSatellites = gpsSatellites; }
    public Integer getRtkSatellites() { return rtkSatellites; }
    public void setRtkSatellites(Integer rtkSatellites) { this.rtkSatellites = rtkSatellites; }
    public String getRtkStatus() { return rtkStatus; }
    public void setRtkStatus(String rtkStatus) { this.rtkStatus = rtkStatus; }
    public BigDecimal getBatteryPercent() { return batteryPercent; }
    public void setBatteryPercent(BigDecimal batteryPercent) { this.batteryPercent = batteryPercent; }
    public Integer getRemainingFlightTime() { return remainingFlightTime; }
    public void setRemainingFlightTime(Integer remainingFlightTime) { this.remainingFlightTime = remainingFlightTime; }
    public Double getGimbalPitch() { return gimbalPitch; }
    public void setGimbalPitch(Double gimbalPitch) { this.gimbalPitch = gimbalPitch; }
    public Double getGimbalRoll() { return gimbalRoll; }
    public void setGimbalRoll(Double gimbalRoll) { this.gimbalRoll = gimbalRoll; }
    public Double getGimbalYaw() { return gimbalYaw; }
    public void setGimbalYaw(Double gimbalYaw) { this.gimbalYaw = gimbalYaw; }
    public String getFlightMode() { return flightMode; }
    public void setFlightMode(String flightMode) { this.flightMode = flightMode; }
    public UUID getMissionId() { return missionId; }
    public void setMissionId(UUID missionId) { this.missionId = missionId; }
    public String getCommunicationStatus() { return communicationStatus; }
    public void setCommunicationStatus(String communicationStatus) { this.communicationStatus = communicationStatus; }
    public UUID getRawDataId() { return rawDataId; }
    public void setRawDataId(UUID rawDataId) { this.rawDataId = rawDataId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.forestfire.uav.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 设备表实体 — 对应 db/migration/V1__baseline_schema.sql 的 uav_device（23 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                      → id（应用侧 UUID.randomUUID()，DDL 无 DB 默认）</li>
 *   <li>device_code VARCHAR(64) NOT NULL UNIQUE → device_code</li>
 *   <li>device_name VARCHAR(128) NOT NULL       → device_name</li>
 *   <li>manufacturer VARCHAR(64)        → manufacturer</li>
 *   <li>model VARCHAR(128)              → model</li>
 *   <li>serial_number VARCHAR(128)      → serial_number</li>
 *   <li>firmware_version VARCHAR(128)   → firmware_version</li>
 *   <li>adapter_type VARCHAR(32) NOT NULL       → adapter_type</li>
 *   <li>device_status VARCHAR(32) NOT NULL      → device_status</li>
 *   <li>home_latitude DOUBLE PRECISION  → home_latitude</li>
 *   <li>home_longitude DOUBLE PRECISION → home_longitude</li>
 *   <li>home_geometry geometry(Point,4326)   → 【不映射】PostGIS 空间列，可空；
 *       避免引入 hibernate-spatial 依赖，INSERT 不含该列时 DB 填 NULL</li>
 *   <li>current_latitude DOUBLE PRECISION    → current_latitude</li>
 *   <li>current_longitude DOUBLE PRECISION   → current_longitude</li>
 *   <li>current_height DOUBLE PRECISION      → current_height</li>
 *   <li>current_geometry geometry(Point,4326)→ 【不映射】同 home_geometry</li>
 *   <li>battery_percent NUMERIC(5,2)    → battery_percent（BigDecimal）</li>
 *   <li>remaining_flight_time INTEGER   → remaining_flight_time</li>
 *   <li>last_online_at TIMESTAMPTZ      → last_online_at（Instant）</li>
 *   <li>last_telemetry_at TIMESTAMPTZ   → last_telemetry_at（Instant）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL → created_at（应用赋值，NOT NULL 覆盖）</li>
 *   <li>updated_at TIMESTAMPTZ NOT NULL → updated_at（应用赋值，NOT NULL 覆盖）</li>
 *   <li>deleted_at TIMESTAMPTZ          → deleted_at</li>
 * </ol>
 * <p>NOT NULL 列（id/device_code/device_name/adapter_type/device_status/created_at/updated_at）
 * 全部由应用赋值；23 列中 21 列映射、2 个 PostGIS geometry 列有意不映射（可空）。</p>
 *
 * <p>时间列映射：java.time.Instant 走 Hibernate 6 默认 TIMESTAMP_UTC（PostgreSQL 上即
 * timestamptz，UTC 绑定），无需额外注解；ddl-auto=none 不做 schema 校验。</p>
 */
@Entity
@Table(name = "uav_device")
public class UavDeviceEntity {

    /** DDL: id UUID PRIMARY KEY（无 DB 默认，应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: device_code VARCHAR(64) NOT NULL UNIQUE */
    @Column(name = "device_code", nullable = false)
    private String deviceCode;

    /** DDL: device_name VARCHAR(128) NOT NULL */
    @Column(name = "device_name", nullable = false)
    private String deviceName;

    /** DDL: manufacturer VARCHAR(64) */
    @Column(name = "manufacturer")
    private String manufacturer;

    /** DDL: model VARCHAR(128) */
    @Column(name = "model")
    private String model;

    /** DDL: serial_number VARCHAR(128) */
    @Column(name = "serial_number")
    private String serialNumber;

    /** DDL: firmware_version VARCHAR(128) */
    @Column(name = "firmware_version")
    private String firmwareVersion;

    /** DDL: adapter_type VARCHAR(32) NOT NULL */
    @Column(name = "adapter_type", nullable = false)
    private String adapterType;

    /** DDL: device_status VARCHAR(32) NOT NULL — DeviceStatus 枚举值（enums.yaml 第7项） */
    @Column(name = "device_status", nullable = false)
    private String deviceStatus;

    /** DDL: home_latitude DOUBLE PRECISION */
    @Column(name = "home_latitude")
    private Double homeLatitude;

    /** DDL: home_longitude DOUBLE PRECISION */
    @Column(name = "home_longitude")
    private Double homeLongitude;

    /** DDL: current_latitude DOUBLE PRECISION */
    @Column(name = "current_latitude")
    private Double currentLatitude;

    /** DDL: current_longitude DOUBLE PRECISION */
    @Column(name = "current_longitude")
    private Double currentLongitude;

    /** DDL: current_height DOUBLE PRECISION */
    @Column(name = "current_height")
    private Double currentHeight;

    /** DDL: battery_percent NUMERIC(5,2) */
    @Column(name = "battery_percent")
    private BigDecimal batteryPercent;

    /** DDL: remaining_flight_time INTEGER */
    @Column(name = "remaining_flight_time")
    private Integer remainingFlightTime;

    /** DDL: last_online_at TIMESTAMPTZ */
    @Column(name = "last_online_at")
    private Instant lastOnlineAt;

    /** DDL: last_telemetry_at TIMESTAMPTZ */
    @Column(name = "last_telemetry_at")
    private Instant lastTelemetryAt;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** DDL: updated_at TIMESTAMPTZ NOT NULL */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** DDL: deleted_at TIMESTAMPTZ */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    public String getAdapterType() { return adapterType; }
    public void setAdapterType(String adapterType) { this.adapterType = adapterType; }
    public String getDeviceStatus() { return deviceStatus; }
    public void setDeviceStatus(String deviceStatus) { this.deviceStatus = deviceStatus; }
    public Double getHomeLatitude() { return homeLatitude; }
    public void setHomeLatitude(Double homeLatitude) { this.homeLatitude = homeLatitude; }
    public Double getHomeLongitude() { return homeLongitude; }
    public void setHomeLongitude(Double homeLongitude) { this.homeLongitude = homeLongitude; }
    public Double getCurrentLatitude() { return currentLatitude; }
    public void setCurrentLatitude(Double currentLatitude) { this.currentLatitude = currentLatitude; }
    public Double getCurrentLongitude() { return currentLongitude; }
    public void setCurrentLongitude(Double currentLongitude) { this.currentLongitude = currentLongitude; }
    public Double getCurrentHeight() { return currentHeight; }
    public void setCurrentHeight(Double currentHeight) { this.currentHeight = currentHeight; }
    public BigDecimal getBatteryPercent() { return batteryPercent; }
    public void setBatteryPercent(BigDecimal batteryPercent) { this.batteryPercent = batteryPercent; }
    public Integer getRemainingFlightTime() { return remainingFlightTime; }
    public void setRemainingFlightTime(Integer remainingFlightTime) { this.remainingFlightTime = remainingFlightTime; }
    public Instant getLastOnlineAt() { return lastOnlineAt; }
    public void setLastOnlineAt(Instant lastOnlineAt) { this.lastOnlineAt = lastOnlineAt; }
    public Instant getLastTelemetryAt() { return lastTelemetryAt; }
    public void setLastTelemetryAt(Instant lastTelemetryAt) { this.lastTelemetryAt = lastTelemetryAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}

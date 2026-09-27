package com.forestfire.uav.device;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 设备视图 DTO（只在响应层暴露，不含 deleted_at 等内部字段）。
 */
public final class DeviceViews {

    private DeviceViews() {
    }

    /** GET /api/v1/uavs 列表项：id/device_code/device_name/device_status/当前坐标/电量 */
    public record UavDeviceSummary(
            UUID id,
            String deviceCode,
            String deviceName,
            String deviceStatus,
            Double currentLatitude,
            Double currentLongitude,
            BigDecimal batteryPercent
    ) {
    }

    /** GET /api/v1/uavs/{uavId} 单设备详情 */
    public record UavDeviceDetail(
            UUID id,
            String deviceCode,
            String deviceName,
            String manufacturer,
            String model,
            String serialNumber,
            String firmwareVersion,
            String adapterType,
            String deviceStatus,
            Double homeLatitude,
            Double homeLongitude,
            Double currentLatitude,
            Double currentLongitude,
            Double currentHeight,
            BigDecimal batteryPercent,
            Integer remainingFlightTime,
            java.time.Instant lastOnlineAt,
            java.time.Instant lastTelemetryAt,
            java.time.Instant createdAt
    ) {
    }

    /**
     * GET /api/v1/uavs/{uavId}/state 实时状态摘要（07号 API 文档第10章结构）。
     * gpsStatus 无落库列（uav_telemetry 只有 gps_satellites），无数据时为 null。
     */
    public record UavStateSummary(
            String deviceId,
            String status,
            Double latitude,
            Double longitude,
            Double altitude,
            Double heading,
            Double speed,
            BigDecimal battery,
            String gpsStatus,
            String rtkStatus
    ) {
    }
}

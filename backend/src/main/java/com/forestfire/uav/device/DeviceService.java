package com.forestfire.uav.device;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.telemetry.UavTelemetryEntity;
import com.forestfire.uav.telemetry.UavTelemetryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 设备查询服务：设备列表 / 单设备详情 / 实时状态摘要。
 */
@Service
public class DeviceService {

    private final UavDeviceRepository deviceRepository;
    private final UavTelemetryRepository telemetryRepository;

    public DeviceService(UavDeviceRepository deviceRepository,
                         UavTelemetryRepository telemetryRepository) {
        this.deviceRepository = deviceRepository;
        this.telemetryRepository = telemetryRepository;
    }

    /** GET /api/v1/uavs — 设备列表 */
    @Transactional(readOnly = true)
    public List<DeviceViews.UavDeviceSummary> listDevices() {
        return deviceRepository.findAll().stream()
                .map(d -> new DeviceViews.UavDeviceSummary(
                        d.getId(),
                        d.getDeviceCode(),
                        d.getDeviceName(),
                        d.getDeviceStatus(),
                        d.getCurrentLatitude(),
                        d.getCurrentLongitude(),
                        d.getBatteryPercent()))
                .toList();
    }

    /** GET /api/v1/uavs/{uavId} — 单设备详情；不存在 → 40401 */
    @Transactional(readOnly = true)
    public DeviceViews.UavDeviceDetail getDevice(String uavId) {
        UavDeviceEntity d = requireDevice(uavId);
        return new DeviceViews.UavDeviceDetail(
                d.getId(),
                d.getDeviceCode(),
                d.getDeviceName(),
                d.getManufacturer(),
                d.getModel(),
                d.getSerialNumber(),
                d.getFirmwareVersion(),
                d.getAdapterType(),
                d.getDeviceStatus(),
                d.getHomeLatitude(),
                d.getHomeLongitude(),
                d.getCurrentLatitude(),
                d.getCurrentLongitude(),
                d.getCurrentHeight(),
                d.getBatteryPercent(),
                d.getRemainingFlightTime(),
                d.getLastOnlineAt(),
                d.getLastTelemetryAt(),
                d.getCreatedAt());
    }

    /**
     * GET /api/v1/uavs/{uavId}/state — 实时状态摘要（07号第10章结构）：
     * {deviceId,status,latitude,longitude,altitude,heading,speed,battery,gpsStatus,rtkStatus}。
     * 取最新一条 uav_telemetry；无遥测时退回 uav_device 快照字段。
     */
    @Transactional(readOnly = true)
    public DeviceViews.UavStateSummary getState(String uavId) {
        UavDeviceEntity device = requireDevice(uavId);
        UavTelemetryEntity latest =
                telemetryRepository.findLatestByUavId(device.getId()).orElse(null);

        Double latitude = latest != null ? latest.getLatitude() : device.getCurrentLatitude();
        Double longitude = latest != null ? latest.getLongitude() : device.getCurrentLongitude();
        Double altitude = latest != null ? latest.getHeight() : device.getCurrentHeight();
        Double heading = latest != null ? latest.getHeading() : null;
        Double speed = latest != null ? latest.getHorizontalSpeed() : null;
        java.math.BigDecimal battery = latest != null
                ? latest.getBatteryPercent() : device.getBatteryPercent();
        // gpsStatus：uav_telemetry 无对应落库列（仅 gps_satellites），MVP 返回 null
        String gpsStatus = null;
        String rtkStatus = latest != null ? latest.getRtkStatus() : null;

        return new DeviceViews.UavStateSummary(
                device.getDeviceCode(),
                device.getDeviceStatus(),
                latitude,
                longitude,
                altitude,
                heading,
                speed,
                battery,
                gpsStatus,
                rtkStatus);
    }

    /** 按 device_code 取设备，不存在抛 40401 路径资源不存在 */
    public UavDeviceEntity requireDevice(String uavId) {
        return deviceRepository.findByDeviceCode(uavId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "uav not found: " + uavId));
    }
}

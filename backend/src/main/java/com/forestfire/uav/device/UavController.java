package com.forestfire.uav.device;

import com.forestfire.uav.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备查询端点：
 * <ul>
 *   <li>GET /api/v1/uavs — 设备列表</li>
 *   <li>GET /api/v1/uavs/{uavId} — 单设备详情（40401 当不存在）</li>
 *   <li>GET /api/v1/uavs/{uavId}/state — 实时状态摘要（07号第10章）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/uavs")
public class UavController {

    private final DeviceService deviceService;

    public UavController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public ApiResponse<List<DeviceViews.UavDeviceSummary>> list() {
        return ApiResponse.ok(deviceService.listDevices());
    }

    @GetMapping("/{uavId}")
    public ApiResponse<DeviceViews.UavDeviceDetail> detail(@PathVariable String uavId) {
        return ApiResponse.ok(deviceService.getDevice(uavId));
    }

    @GetMapping("/{uavId}/state")
    public ApiResponse<DeviceViews.UavStateSummary> state(@PathVariable String uavId) {
        return ApiResponse.ok(deviceService.getState(uavId));
    }
}

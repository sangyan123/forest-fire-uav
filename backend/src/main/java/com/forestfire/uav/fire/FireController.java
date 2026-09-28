package com.forestfire.uav.fire;

import com.forestfire.uav.common.ApiResponse;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 火情端点（Phase 3/4）：
 * <ul>
 *   <li>GET  /api/v1/fire/incidents — 事件列表（含火点坐标/最近检测置信度，创建倒序）</li>
 *   <li>GET  /api/v1/fire/incidents/{id} — 事件详情（含 fire_point、detections 概要）</li>
 *   <li>PATCH /api/v1/fire/incidents/{id}/status — 状态机迁移（非法迁移 40003）</li>
 *   <li>POST /api/v1/fire/incidents/{id}/verification — F04 核验</li>
 *   <li>POST /api/v1/fire/points、GET /api/v1/fire/points/{id} — 火点创建/查询（07号第13章）</li>
 *   <li>POST /api/v1/fire/detections — 手动创建检测记录（07号第11章，demo 备用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class FireController {

    private final FireIncidentService fireIncidentService;

    public FireController(FireIncidentService fireIncidentService) {
        this.fireIncidentService = fireIncidentService;
    }

    @GetMapping("/fire/incidents")
    public ApiResponse<List<FireViews.IncidentSummary>> list() {
        return ApiResponse.ok(fireIncidentService.list());
    }

    @GetMapping("/fire/incidents/{id}")
    public ApiResponse<FireViews.IncidentDetail> detail(@PathVariable String id) {
        return ApiResponse.ok(fireIncidentService.getDetail(parseUuid(id, "incidentId")));
    }

    @PatchMapping("/fire/incidents/{id}/status")
    public ApiResponse<FireViews.IncidentDetail> updateStatus(
            @PathVariable String id,
            @RequestBody FireIncidentService.StatusUpdateRequest request) {
        return ApiResponse.ok(fireIncidentService.updateStatus(parseUuid(id, "incidentId"), request));
    }

    @PostMapping("/fire/incidents/{id}/verification")
    public ApiResponse<FireViews.VerificationView> verify(
            @PathVariable String id,
            @RequestBody(required = false) FireIncidentService.VerificationRequest request) {
        return ApiResponse.ok(fireIncidentService.verify(parseUuid(id, "incidentId"), request));
    }

    @PostMapping("/fire/points")
    public ApiResponse<FireViews.PointView> createFirePoint(
            @RequestBody FireIncidentService.FirePointCreateRequest request) {
        return ApiResponse.ok(fireIncidentService.createFirePoint(request));
    }

    @GetMapping("/fire/points/{id}")
    public ApiResponse<FireViews.PointView> getFirePoint(@PathVariable String id) {
        return ApiResponse.ok(fireIncidentService.getFirePoint(parseUuid(id, "pointId")));
    }

    @PostMapping("/fire/detections")
    public ApiResponse<FireViews.DetectionView> createDetection(
            @RequestBody FireIncidentService.ManualDetectionRequest request) {
        return ApiResponse.ok(fireIncidentService.createDetection(request));
    }

    /** 路径 id 必须是 UUID，非法 → 40001 参数错误 */
    private static UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    name + " must be a UUID: " + value);
        }
    }
}

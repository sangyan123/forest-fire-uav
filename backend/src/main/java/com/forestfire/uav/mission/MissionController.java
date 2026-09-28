package com.forestfire.uav.mission;

import com.forestfire.uav.common.ApiResponse;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 任务端点：
 * <ul>
 *   <li>POST /api/v1/missions — 创建任务（FIRE_VERIFICATION）</li>
 *   <li>GET  /api/v1/missions — 任务列表（创建倒序）</li>
 *   <li>GET  /api/v1/missions/{id} — 任务详情（含航点/最新调度）</li>
 *   <li>POST /api/v1/missions/{id}/start — MVP 调度闭环（F09-Basic）并自动下发 GOTO</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class MissionController {

    private final MissionService missionService;

    public MissionController(MissionService missionService) {
        this.missionService = missionService;
    }

    @PostMapping("/missions")
    public ApiResponse<MissionViews.MissionView> create(
            @RequestBody MissionService.MissionCreateRequest request) {
        return ApiResponse.ok(missionService.create(request));
    }

    @GetMapping("/missions")
    public ApiResponse<List<MissionViews.MissionView>> list() {
        return ApiResponse.ok(missionService.list());
    }

    @GetMapping("/missions/{id}")
    public ApiResponse<MissionViews.MissionView> get(@PathVariable String id) {
        return ApiResponse.ok(missionService.getMissionView(parseUuid(id)));
    }

    @PostMapping("/missions/{id}/start")
    public ApiResponse<MissionViews.MissionStartResult> start(@PathVariable String id) {
        return ApiResponse.ok(missionService.start(parseUuid(id)));
    }

    /** 路径 id 必须是 UUID，非法 → 40001 参数错误 */
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "missionId must be a UUID: " + value);
        }
    }
}

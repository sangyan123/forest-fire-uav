package com.forestfire.uav.patrol;

import com.forestfire.uav.common.ApiResponse;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时巡逻端点：
 * <ul>
 *   <li>GET /api/v1/patrol-schedule — 查询计划（当天生效窗口：正常或禁期）与当前班次</li>
 *   <li>PUT /api/v1/patrol-schedule — 快捷指令开关：manual=true 立即起飞开手动班次，
 *       manual=false 结束手动班次（日常计划不受影响）</li>
 *   <li>PUT /api/v1/patrol-schedule/closure — 禁期（封山期）配置：日期范围内按独立窗口巡逻，
 *       到期自动切回正常计划</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class PatrolScheduleController {

    private final PatrolScheduleService patrolScheduleService;

    public PatrolScheduleController(PatrolScheduleService patrolScheduleService) {
        this.patrolScheduleService = patrolScheduleService;
    }

    @GetMapping("/patrol-schedule")
    public ApiResponse<PatrolScheduleService.PatrolScheduleView> get() {
        return ApiResponse.ok(patrolScheduleService.view());
    }

    @PutMapping("/patrol-schedule")
    public ApiResponse<PatrolScheduleService.PatrolScheduleView> toggle(
            @RequestBody PatrolScheduleService.ManualToggleRequest request) {
        if (request == null || request.manual() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "manual is required (true/false)");
        }
        return ApiResponse.ok(patrolScheduleService.setManual(request.manual()));
    }

    @PutMapping("/patrol-schedule/closure")
    public ApiResponse<PatrolScheduleService.PatrolScheduleView> closure(
            @RequestBody PatrolScheduleService.ClosureRequest request) {
        if (request == null || request.enabled() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "enabled is required (true/false)");
        }
        return ApiResponse.ok(patrolScheduleService.setClosure(request));
    }
}

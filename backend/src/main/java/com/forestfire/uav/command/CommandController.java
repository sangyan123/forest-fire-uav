package com.forestfire.uav.command;

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

import java.util.UUID;

/**
 * 命令端点：
 * <ul>
 *   <li>POST /api/v1/uavs/{uavId}/commands — 创建并下发命令</li>
 *   <li>PATCH /api/v1/commands/{commandId}/status — gateway 回调状态更新</li>
 *   <li>GET /api/v1/commands/{commandId} — 命令记录 + 结果</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class CommandController {

    private final CommandService commandService;

    public CommandController(CommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping("/uavs/{uavId}/commands")
    public ApiResponse<CommandService.CommandView> create(
            @PathVariable String uavId,
            @RequestBody CommandService.CommandCreateRequest request) {
        return ApiResponse.ok(commandService.create(uavId, request));
    }

    @PatchMapping("/commands/{commandId}/status")
    public ApiResponse<CommandService.CommandView> updateStatus(
            @PathVariable String commandId,
            @RequestBody CommandService.CommandStatusUpdateRequest request) {
        return ApiResponse.ok(commandService.updateStatus(parseUuid(commandId), request));
    }

    @GetMapping("/commands/{commandId}")
    public ApiResponse<CommandService.CommandView> get(@PathVariable String commandId) {
        return ApiResponse.ok(commandService.get(parseUuid(commandId)));
    }

    /** commandId 必须是 UUID，非法 → 40001 参数错误 */
    private static UUID parseUuid(String commandId) {
        try {
            return UUID.fromString(commandId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "commandId must be a UUID: " + commandId);
        }
    }
}

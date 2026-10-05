package com.forestfire.uav.command;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.device.UavDeviceEntity;
import com.forestfire.uav.device.UavDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 命令服务：
 * 1) POST /api/v1/uavs/{uavId}/commands — 创建命令并 HTTP 下发到 gateway；
 * 2) PATCH /api/v1/commands/{commandId}/status — gateway 回调更新状态，终态落 uav_command_result；
 * 3) GET /api/v1/commands/{commandId} — 命令记录 + 结果。
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    /** DeviceCommandStatus（enums.yaml 9b，设备侧命令状态，9 值） */
    private static final Set<String> DEVICE_COMMAND_STATUSES = Set.of(
            "RECEIVED", "VALIDATING", "ACCEPTED", "EXECUTING",
            "SUCCESS", "FAILED", "TIMEOUT", "REJECTED", "CANCELLED");

    /** 终态集合（到达时插入 uav_command_result） */
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "SUCCESS", "FAILED", "TIMEOUT", "REJECTED", "CANCELLED");

    private static final String ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final UavCommandRepository commandRepository;
    private final UavCommandResultRepository resultRepository;
    private final UavDeviceRepository deviceRepository;
    private final RestClient gatewayRestClient;

    private final SecureRandom random = new SecureRandom();

    public CommandService(UavCommandRepository commandRepository,
                          UavCommandResultRepository resultRepository,
                          UavDeviceRepository deviceRepository,
                          RestClient gatewayRestClient) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.deviceRepository = deviceRepository;
        this.gatewayRestClient = gatewayRestClient;
    }

    /** 命令视图（命令记录 + 最新结果 + 创建响应的下发说明） */
    public record CommandView(
            UUID id,
            String commandNo,
            UUID uavId,
            UUID missionId,
            String commandType,
            String priority,
            Map<String, Object> payload,
            String status,
            Instant createdAt,
            Instant sentAt,
            Instant ackAt,
            Instant completedAt,
            String errorCode,
            String errorMessage,
            Boolean gatewayDelivered,
            String gatewayNote,
            ResultView result
    ) {
    }

    public record ResultView(
            UUID id,
            UUID commandId,
            String executionStatus,
            Map<String, Object> deviceResponse,
            Instant executionStartAt,
            Instant executionEndAt,
            String errorCode,
            String errorMessage,
            Instant createdAt
    ) {
    }

    /** 创建命令请求体：{commandType, params对象} */
    public record CommandCreateRequest(String commandType, Map<String, Object> params) {
    }

    /** 状态回调请求体：{status, message?, executionTimeMs?} */
    public record CommandStatusUpdateRequest(String status, String message, Long executionTimeMs) {
    }

    /**
     * 创建命令：插入 uav_command（status=CREATED）→ HTTP POST gateway /internal/commands。
     * gateway 可达（2xx）→ status=SENT、sent_at=now；
     * gateway 不可达/非 2xx → status=FAILED（error_code=GATEWAY_UNREACHABLE）并在响应中说明，
     * 同时补插一条 uav_command_result 便于 GET 命令详情可见失败原因。
     */
    @Transactional
    public CommandView create(String uavId, CommandCreateRequest request) {
        if (request == null || request.commandType() == null || request.commandType().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "commandType is required");
        }
        UavDeviceEntity device = deviceRepository.findByDeviceCode(uavId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "uav not found: " + uavId));

        Instant now = Instant.now();
        UavCommandEntity cmd = new UavCommandEntity();
        cmd.setId(UUID.randomUUID());                          // id = UUID.randomUUID()
        cmd.setCommandNo("CMD-" + random8());                  // command_no = "CMD-"+8位随机
        cmd.setUavId(device.getId());                          // uav_id = device.id（UUID）
        cmd.setCommandType(request.commandType());             // command_type
        cmd.setPriority("NORMAL");                             // priority 默认值
        cmd.setPayload(request.params() == null ? Map.of() : request.params()); // payload JSONB
        cmd.setStatus("CREATED");                              // status NOT NULL 默认
        cmd.setCreatedAt(now);                                 // created_at NOT NULL
        cmd = commandRepository.save(cmd);

        boolean delivered = false;
        String note = null;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("deviceId", uavId);
            body.put("commandId", cmd.getId().toString());
            body.put("commandType", cmd.getCommandType());
            body.put("payload", cmd.getPayload());
            gatewayRestClient.post()
                    .uri("/internal/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            delivered = true;
            cmd.setStatus("SENT");
            cmd.setSentAt(Instant.now());
            commandRepository.save(cmd);
        } catch (Exception e) {
            // gateway 不可达或返回非 2xx：命令置 FAILED，响应中说明
            delivered = false;
            note = "gateway delivery failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
            log.warn("command {} gateway delivery failed: {}", cmd.getId(), note);
            cmd.setStatus("FAILED");
            cmd.setErrorCode("GATEWAY_UNREACHABLE");
            cmd.setErrorMessage(note);
            cmd.setCompletedAt(Instant.now());
            commandRepository.save(cmd);
            // 补插结果记录，保证命令详情可见失败原因
            UavCommandResultEntity r = new UavCommandResultEntity();
            r.setId(UUID.randomUUID());
            r.setCommandId(cmd.getId());
            r.setExecutionStatus("FAILED");
            r.setDeviceResponse(Map.of("error", "GATEWAY_UNREACHABLE", "message", note));
            r.setExecutionEndAt(Instant.now());
            r.setErrorCode("GATEWAY_UNREACHABLE");
            r.setErrorMessage(note);
            r.setCreatedAt(Instant.now());
            resultRepository.save(r);
        }

        return toView(cmd, delivered, note);
    }

    /**
     * gateway 回调：更新 uav_command.status（DeviceCommandStatus 9 值之一）；
     * 终态（SUCCESS/FAILED/TIMEOUT/REJECTED/CANCELLED）时插入 uav_command_result。
     */
    @Transactional
    public CommandView updateStatus(UUID commandId, CommandStatusUpdateRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "status is required");
        }
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!DEVICE_COMMAND_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "invalid DeviceCommandStatus: " + request.status()
                            + " (allowed: " + DEVICE_COMMAND_STATUSES + ")");
        }

        UavCommandEntity cmd = commandRepository.findByIdForUpdate(commandId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "command not found: " + commandId));

        // 终态守卫：已终态的命令不再接受任何回写（含重复终态与迟到的 EXECUTING/RECEIVED），
        // 防止并发回调乱序把终态覆盖回非终态（命令永久卡在 EXECUTING）
        if (TERMINAL_STATUSES.contains(cmd.getStatus())) {
            log.info("command {} already terminal ({}), ignore status update to {}",
                    commandId, cmd.getStatus(), status);
            return toView(cmd, null, null);
        }

        Instant now = Instant.now();
        cmd.setStatus(status);
        if ("RECEIVED".equals(status) && cmd.getAckAt() == null) {
            cmd.setAckAt(now);
        }

        boolean terminal = TERMINAL_STATUSES.contains(status);
        if (terminal) {
            cmd.setCompletedAt(now);
            UavCommandResultEntity r = new UavCommandResultEntity();
            r.setId(UUID.randomUUID());
            r.setCommandId(cmd.getId());
            r.setExecutionStatus(status);
            Map<String, Object> deviceResponse = new HashMap<>();
            deviceResponse.put("status", status);
            if (request.message() != null) {
                deviceResponse.put("message", request.message());
            }
            if (request.executionTimeMs() != null) {
                deviceResponse.put("executionTimeMs", request.executionTimeMs());
            }
            r.setDeviceResponse(deviceResponse);
            if (request.executionTimeMs() != null) {
                // 由回调时长推算开始时刻
                r.setExecutionStartAt(now.minusMillis(request.executionTimeMs()));
            }
            r.setExecutionEndAt(now);
            // 失败族终态把 message 落入 error_message
            if (!"SUCCESS".equals(status) && !"CANCELLED".equals(status)) {
                r.setErrorMessage(request.message());
            }
            r.setCreatedAt(now);
            resultRepository.save(r);
        }

        commandRepository.save(cmd);
        return toView(cmd, null, null);
    }

    /** GET /api/v1/commands/{commandId} — 命令记录 + 结果 */
    @Transactional(readOnly = true)
    public CommandView get(UUID commandId) {
        UavCommandEntity cmd = commandRepository.findById(commandId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "command not found: " + commandId));
        return toView(cmd, null, null);
    }

    // ---------------- 内部工具 ----------------

    private CommandView toView(UavCommandEntity cmd, Boolean delivered, String note) {
        ResultView resultView = resultRepository
                .findFirstByCommandIdOrderByCreatedAtDesc(cmd.getId())
                .map(r -> new ResultView(
                        r.getId(),
                        r.getCommandId(),
                        r.getExecutionStatus(),
                        r.getDeviceResponse(),
                        r.getExecutionStartAt(),
                        r.getExecutionEndAt(),
                        r.getErrorCode(),
                        r.getErrorMessage(),
                        r.getCreatedAt()))
                .orElse(null);
        return new CommandView(
                cmd.getId(),
                cmd.getCommandNo(),
                cmd.getUavId(),
                cmd.getMissionId(),
                cmd.getCommandType(),
                cmd.getPriority(),
                cmd.getPayload(),
                cmd.getStatus(),
                cmd.getCreatedAt(),
                cmd.getSentAt(),
                cmd.getAckAt(),
                cmd.getCompletedAt(),
                cmd.getErrorCode(),
                cmd.getErrorMessage(),
                delivered,
                note,
                resultView);
    }

    /** 8 位大写字母+数字随机串（command_no，UNIQUE；碰撞概率可忽略） */
    private String random8() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
}

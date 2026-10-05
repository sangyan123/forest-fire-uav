package com.forestfire.uav.patrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forestfire.uav.command.CommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 定时巡逻计划（2026-10-05 demo 增补，默认值机器来源 constants.yaml#patrol_schedule）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li><b>日常计划（始终生效）</b>：每天 {@code start-time}（默认 08:00，本地时区）自动起飞，
 *       巡逻 {@code duration-hours} 小时（默认 10h，即到 18:00）后自动返航降落，归巢充电，次日循环。</li>
 *   <li><b>手动班次（快捷指令开关）</b>：打开 → 立即起飞（不等 08:00），飞到下一个 18:00（窗口结束时刻）
 *       自动返航，返航降落后开关自动回位；关闭 → 手动班次立即结束并返航，日常计划不受影响。</li>
 *   <li><b>收敛循环</b>：每 30 秒对照 mock-uav 状态向期望状态收敛——班次中意外落地自动重新起飞、
 *       到期自动返航、窗口内自动开班。只在空闲态（无火情场景、无 GOTO 目标、非断联静默）动作，
 *       不打断演示场景与手动指令。</li>
 * </ul>
 *
 * <p>班次为<b>内存态</b>：后端重启后班次清空，收敛循环按配置默认值自愈（窗口内重新开班 / 窗口外归巢）；
 * 真实任务计划系统（cron 持久化、多机排班）属后续阶段。</p>
 */
@Service
public class PatrolScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PatrolScheduleService.class);

    /** 收敛循环周期（毫秒） */
    private static final long RECONCILE_INTERVAL_MS = 30_000L;

    /** 巡逻计划来源：手动（快捷指令开关）/ 计划（每日窗口自动开班） */
    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_SCHEDULED = "SCHEDULED";

    /** 当前班次（returning=已下发 RETURN_HOME，等待到家降落） */
    public record Shift(String origin, Instant startedAt, Instant endAt, boolean returning) {
    }

    /** GET/PUT 响应视图 */
    public record PatrolScheduleView(String startTime, int durationHours, String zone,
                                     Shift shift) {
    }

    /** PUT 请求体：manual=true 打开开关（立即起飞），false 关闭 */
    public record ManualToggleRequest(Boolean manual) {
    }

    private final CommandService commandService;
    private final RestClient mockRestClient;
    private final ObjectMapper objectMapper;
    private final String deviceCode;
    private final LocalTime windowStart;
    private final java.time.Duration windowDuration;
    private final ZoneId zone;

    /** 当前班次（内存态，volatile 保证跨调度线程可见） */
    private volatile Shift shift;

    public PatrolScheduleService(
            CommandService commandService,
            RestClient mockRestClient,
            ObjectMapper objectMapper,
            @Value("${patrol.schedule.device-code:UAV-001}") String deviceCode,
            @Value("${patrol.schedule.start-time:08:00}") String startTime,
            @Value("${patrol.schedule.duration-hours:10}") int durationHours,
            @Value("${patrol.schedule.zone:Asia/Shanghai}") String zone) {
        this.commandService = commandService;
        this.mockRestClient = mockRestClient;
        this.objectMapper = objectMapper;
        this.deviceCode = deviceCode;
        try {
            this.windowStart = LocalTime.parse(startTime.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("patrol.schedule.start-time must be HH:mm, got: " + startTime, e);
        }
        if (durationHours < 1 || durationHours > 23) {
            throw new IllegalArgumentException("patrol.schedule.duration-hours must be 1..23, got: " + durationHours);
        }
        this.windowDuration = java.time.Duration.ofHours(durationHours);
        this.zone = ZoneId.of(zone.trim());
        log.info("Patrol schedule: daily {} ~ {} ({}), device={}",
                this.windowStart, this.windowStart.plus(this.windowDuration), this.zone, deviceCode);
    }

    // ---------------- 查询 / 开关 ----------------

    public synchronized PatrolScheduleView view() {
        return new PatrolScheduleView(
                windowStart.toString(), Math.toIntExact(windowDuration.toHours()), zone.getId(), shift);
    }

    /** 开关：true=立即开手动班次（若已在地则同步下发 TAKEOFF）；false=结束手动班次（空中则立即返航） */
    public synchronized PatrolScheduleView setManual(boolean manual) {
        Shift current = shift;
        if (manual) {
            if (current != null && ORIGIN_MANUAL.equals(current.origin())) {
                log.info("Patrol toggle ON: manual shift already active (endAt={})", current.endAt());
                return view();
            }
            Instant endAt = nextWindowEnd(Instant.now());
            shift = new Shift(ORIGIN_MANUAL, Instant.now(), endAt, false);
            log.info("Patrol manual shift opened: endAt={} (toggle ON)", endAt);
            JsonNode status = fetchSimulatorStatus();
            if (status != null && isIdle(status) && isLanded(status)) {
                sendCommand("TAKEOFF", "patrol manual shift");
            }
            return view();
        }
        if (current != null && ORIGIN_MANUAL.equals(current.origin())) {
            shift = null;
            log.info("Patrol manual shift closed by toggle OFF");
            JsonNode status = fetchSimulatorStatus();
            if (status != null && isIdle(status) && isAirborne(status)) {
                sendCommand("RETURN_HOME", "patrol manual shift closed");
            }
            return view();
        }
        log.info("Patrol toggle OFF: no manual shift active (daily schedule unchanged)");
        return view();
    }

    // ---------------- 收敛循环 ----------------

    @Scheduled(fixedRate = RECONCILE_INTERVAL_MS)
    public void reconcile() {
        JsonNode status = fetchSimulatorStatus();
        if (status == null || !isIdle(status)) {
            return; // mock 未就绪 / 火情场景 / GOTO / 断联静默中：不干预
        }
        boolean airborne = isAirborne(status);
        boolean landed = isLanded(status);
        Instant now = Instant.now();
        Shift s = shift;

        if (s != null && !now.isBefore(s.endAt())) {
            // 班次到期：空中下发返航（仅一次），落地后关闭班次（手动班次开关自动回位）
            if (airborne && !s.returning()) {
                shift = new Shift(s.origin(), s.startedAt(), s.endAt(), true);
                sendCommand("RETURN_HOME", "patrol shift due");
            } else if (landed) {
                log.info("Patrol shift finished (origin={}, landed at home), shift closed", s.origin());
                shift = null;
            }
            return;
        }
        if (s != null) {
            if (landed) {
                // 班次中意外在地：自愈重新起飞
                sendCommand("TAKEOFF", "patrol shift self-heal");
            } else if (airborne && isHovering(status)) {
                // 班次中悬停（GOTO 到位/手动悬停后）：恢复航线巡逻（mock RESUME 就近插回航线）
                sendCommand("RESUME", "patrol shift resume wayline");
            }
            return;
        }
        // 无班次：窗口内（无论落地/滞空）收编为计划班次（滞空则到期返航、落地则立即起飞）；
        // 窗口外滞空自动归巢
        if (inWindow(now)) {
            shift = new Shift(ORIGIN_SCHEDULED, now, windowEndOfToday(now), false);
            log.info("Patrol scheduled shift adopted (in window), endAt={}", shift.endAt());
            if (landed) {
                sendCommand("TAKEOFF", "patrol scheduled window");
            } else if (airborne && isHovering(status)) {
                sendCommand("RESUME", "patrol scheduled shift resume wayline");
            }
        } else if (airborne) {
            sendCommand("RETURN_HOME", "outside patrol window");
        }
    }

    // ---------------- 内部工具 ----------------

    /** 当前时刻是否在每日巡逻窗口内（start ≤ t < start+duration，按配置时区） */
    private boolean inWindow(Instant now) {
        LocalTime t = LocalTime.from(now.atZone(zone));
        LocalTime end = windowStart.plus(windowDuration);
        return !t.isBefore(windowStart) && t.isBefore(end);
    }

    /** 下一个窗口结束时刻（今天未到则今天，已过则明天）——手动班次"飞到 18:00"的端点 */
    private Instant nextWindowEnd(Instant now) {
        LocalTime endTime = windowStart.plus(windowDuration);
        LocalDate today = LocalDate.from(now.atZone(zone));
        Instant todayEnd = today.atTime(endTime).atZone(zone).toInstant();
        return now.isBefore(todayEnd) ? todayEnd : today.plusDays(1).atTime(endTime).atZone(zone).toInstant();
    }

    /** 今日窗口结束时刻（仅在窗口内调用） */
    private Instant windowEndOfToday(Instant now) {
        LocalTime endTime = windowStart.plus(windowDuration);
        return LocalDate.from(now.atZone(zone)).atTime(endTime).atZone(zone).toInstant();
    }

    /** 拉取 mock-uav 状态（失败返回 null，收敛循环跳过本轮） */
    private JsonNode fetchSimulatorStatus() {
        try {
            String body = mockRestClient.get().uri("/simulator/status").retrieve().body(String.class);
            JsonNode envelope = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode data = envelope.path("data");
            return data.isMissingNode() ? envelope : data;
        } catch (Exception e) {
            log.debug("Simulator status fetch failed: {}", e.toString());
            return null;
        }
    }

    /** 空闲态：无火情场景采集/转场、无 GOTO 目标、非断联静默、日常巡检场景 */
    private boolean isIdle(JsonNode status) {
        boolean patrolScenario = "scenario-01".equals(status.path("currentScenarioId").asText(""));
        boolean fireIdle = !status.path("fireScenario").path("active").asBoolean(true);
        boolean noTarget = status.path("target").isNull() || status.path("target").isMissingNode();
        boolean commsOk = !status.path("commsSilent").asBoolean(false);
        return patrolScenario && fireIdle && noTarget && commsOk;
    }

    private boolean isAirborne(JsonNode status) {
        String fs = status.path("flight").path("status").asText("");
        return !fs.isEmpty() && !"LANDED".equals(fs) && !"IDLE".equals(fs) && !"INIT".equals(fs);
    }

    private boolean isLanded(JsonNode status) {
        return "LANDED".equals(status.path("flight").path("status").asText(""));
    }

    /** 悬停态（非场景采集、非 GOTO 执行中）：班次内应恢复航线巡逻 */
    private boolean isHovering(JsonNode status) {
        String mode = status.path("flight").path("mode").asText("");
        return "HOVER".equals(mode) || "HOVERING".equals(mode);
    }

    /** 经完整命令链路下发（落库 → gateway → MQTT → mock 执行 → 回执，全程留档） */
    private void sendCommand(String commandType, String reason) {
        try {
            CommandService.CommandView view = commandService.create(
                    deviceCode, new CommandService.CommandCreateRequest(commandType, null));
            log.info("Patrol schedule command sent: {} ({}) -> commandId={}", commandType, reason, view.id());
        } catch (Exception e) {
            log.warn("Patrol schedule command {} failed ({}): {}", commandType, reason, e.toString());
        }
    }
}

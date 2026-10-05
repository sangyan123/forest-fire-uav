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
import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * 定时巡逻计划（2026-10-05 demo 增补，默认值机器来源 constants.yaml#patrol_schedule）。
 *
 * <p>语义（经用户两轮修订定稿）：</p>
 * <ul>
 *   <li><b>正常巡逻计划</b>：每天 {@code start-time}（默认 08:00，本地时区）自动起飞，
 *       巡逻 {@code duration-hours} 小时（默认 10h，即到 18:00）后自动返航降落，归巢充电，次日循环。</li>
 *   <li><b>禁期巡逻（封山期）</b>：开关<b>只选择计划</b>（正常/禁期），不控制无人机——
 *       森林限制进入期间配置日期范围后，禁期日的自动起飞/返航按独立窗口（默认 06:00+14h=20:00）执行，
 *       到期自动切回正常计划。</li>
 *   <li><b>定时巡逻开关（快捷指令）= 飞不飞的唯一控制</b>：打开 → 立即起飞开班（飞到当天生效窗口
 *       结束时刻自动返航）；关闭 → 立即结束班次并返航；每日计划到点自动起飞时开关自动亮起，
 *       任何降落（含人工点降落）都会结束班次并让开关回位。</li>
 *   <li><b>收敛循环</b>：每 30 秒检查——仅在每日计划<b>起飞时刻</b>（±5 分钟宽限）自动开班一次；
 *       班次到期自动返航；窗口外滞空自动归巢。计划切换（禁期开关）不触发起飞/返航。</li>
 * </ul>
 *
 * <p>班次、禁期配置、当日已起飞标记均为<b>内存态</b>：后端重启后清空，错过当日起飞时刻则次日再飞；
 * 真实任务计划系统（cron 持久化、多机排班）属后续阶段。</p>
 */
@Service
public class PatrolScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PatrolScheduleService.class);

    /** 收敛循环周期（毫秒） */
    private static final long RECONCILE_INTERVAL_MS = 30_000L;

    /** 每日自动起飞的触发宽限（分钟）：仅在窗口开始时刻附近触发一次，错过则次日再飞 */
    private static final long AUTO_START_GRACE_MINUTES = 5L;

    /** 巡逻计划来源：手动（快捷指令开关）/ 计划（每日窗口自动开班） */
    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_SCHEDULED = "SCHEDULED";

    /** 巡逻模式：正常 / 禁期（封山期） */
    public static final String MODE_NORMAL = "NORMAL";
    public static final String MODE_CLOSURE = "CLOSURE";

    /** 当前班次（returning=已下发 RETURN_HOME，等待到家降落） */
    public record Shift(String origin, Instant startedAt, Instant endAt, boolean returning) {
    }

    /** 禁期（封山期）配置：enabled 且日期命中时，当天按禁期窗口巡逻 */
    public record ClosurePlan(boolean enabled, LocalDate startDate, LocalDate endDate,
                              LocalTime startTime, int durationHours) {
    }

    /** PUT /closure 请求体 */
    public record ClosureRequest(Boolean enabled, LocalDate startDate, LocalDate endDate,
                                 LocalTime startTime, Integer durationHours) {
    }

    /** GET/PUT 响应视图：startTime/durationHours 为"今天实际生效"的计划（正常或禁期） */
    public record PatrolScheduleView(String mode, String startTime, int durationHours, String zone,
                                     ClosurePlan closure, Shift shift) {
    }

    /** PUT 请求体：manual=true 打开开关（立即起飞），false 关闭 */
    public record ManualToggleRequest(Boolean manual) {
    }

    /** 当天生效的巡逻窗口（start ~ start+duration） */
    private record Plan(LocalTime start, Duration duration) {
    }

    private final CommandService commandService;
    private final RestClient mockRestClient;
    private final ObjectMapper objectMapper;
    private final String deviceCode;
    private final LocalTime normalStart;
    private final Duration normalDuration;
    private final LocalTime closureStart;
    private final int closureDurationHours;
    private final ZoneId zone;

    /** 当前班次（内存态，volatile 保证跨调度线程可见） */
    private volatile Shift shift;

    /** 禁期配置（内存态；默认禁用，日期范围经 PUT /closure 配置） */
    private volatile ClosurePlan closure;

    /** 当日已自动起飞标记（每日计划班次一天只触发一次；重启后清空） */
    private volatile LocalDate lastAutoStart;

    public PatrolScheduleService(
            CommandService commandService,
            RestClient mockRestClient,
            ObjectMapper objectMapper,
            @Value("${patrol.schedule.device-code:UAV-001}") String deviceCode,
            @Value("${patrol.schedule.start-time:08:00}") String startTime,
            @Value("${patrol.schedule.duration-hours:10}") int durationHours,
            @Value("${patrol.schedule.closure-start-time:06:00}") String closureStartTime,
            @Value("${patrol.schedule.closure-duration-hours:14}") int closureDurationHours,
            @Value("${patrol.schedule.zone:Asia/Shanghai}") String zone) {
        this.commandService = commandService;
        this.mockRestClient = mockRestClient;
        this.objectMapper = objectMapper;
        this.deviceCode = deviceCode;
        this.normalStart = parseTime(startTime, "patrol.schedule.start-time");
        this.normalDuration = validateDuration(durationHours, "patrol.schedule.duration-hours");
        this.closureStart = parseTime(closureStartTime, "patrol.schedule.closure-start-time");
        if (closureDurationHours < 1 || closureDurationHours > 23) {
            throw new IllegalArgumentException("patrol.schedule.closure-duration-hours must be 1..23, got: " + closureDurationHours);
        }
        this.closureDurationHours = closureDurationHours;
        this.zone = ZoneId.of(zone.trim());
        this.closure = new ClosurePlan(false, null, null, closureStart, closureDurationHours);
        log.info("Patrol schedule: normal daily {} ~ {} ({}); closure window {} ~ {}h",
                this.normalStart, this.normalStart.plus(this.normalDuration), this.zone,
                this.closureStart, this.closureDurationHours);
    }

    // ---------------- 查询 / 开关 / 禁期 ----------------

    public synchronized PatrolScheduleView view() {
        LocalDate today = LocalDate.now(zone);
        Plan plan = resolvePlan(today);
        String mode = isClosureDay(today) ? MODE_CLOSURE : MODE_NORMAL;
        return new PatrolScheduleView(mode, plan.start().toString(),
                Math.toIntExact(plan.duration().toHours()), zone.getId(), closure, shift);
    }

    /** 开关：true=立即开班（若已在地则同步下发 TAKEOFF；已有班次则无动作）；false=结束班次（空中则立即返航） */
    public synchronized PatrolScheduleView setManual(boolean manual) {
        Shift current = shift;
        if (manual) {
            if (current != null) {
                log.info("Patrol toggle ON: shift already active ({}, endAt={})", current.origin(), current.endAt());
                return view();
            }
            Instant endAt = nextWindowEnd(Instant.now());
            shift = new Shift(ORIGIN_MANUAL, Instant.now(), endAt, false);
            lastAutoStart = LocalDate.now(zone); // 手动开班视为当日已起飞，避免计划班次重复触发
            log.info("Patrol manual shift opened: endAt={} (toggle ON)", endAt);
            JsonNode status = fetchSimulatorStatus();
            if (status != null && isIdle(status) && isLanded(status)) {
                sendCommand("TAKEOFF", "patrol manual shift");
            }
            return view();
        }
        if (current != null) {
            shift = null;
            log.info("Patrol shift closed by toggle OFF (origin={})", current.origin());
            JsonNode status = fetchSimulatorStatus();
            if (status != null && isIdle(status) && isAirborne(status)) {
                sendCommand("RETURN_HOME", "patrol shift closed by toggle");
            }
            return view();
        }
        log.info("Patrol toggle OFF: no shift active (daily schedule unchanged)");
        return view();
    }

    /** 禁期配置：enabled=true 时日期范围内按禁期窗口巡逻（缺省日期=今天起 30 天，缺省时间=配置默认） */
    public synchronized PatrolScheduleView setClosure(ClosureRequest request) {
        if (request == null || request.enabled() == null) {
            throw new com.forestfire.uav.common.BusinessException(
                    com.forestfire.uav.common.ErrorCode.BAD_REQUEST, "enabled is required (true/false)");
        }
        boolean enabled = request.enabled();
        if (enabled) {
            LocalDate start = request.startDate() != null ? request.startDate() : LocalDate.now(zone);
            LocalDate end = request.endDate() != null ? request.endDate() : start.plusDays(30);
            if (end.isBefore(start)) {
                throw new com.forestfire.uav.common.BusinessException(
                        com.forestfire.uav.common.ErrorCode.BAD_REQUEST, "endDate must not be before startDate");
            }
            LocalTime startT = request.startTime() != null ? request.startTime() : closure.startTime();
            int duration = request.durationHours() != null ? request.durationHours() : closure.durationHours();
            if (duration < 1 || duration > 23) {
                throw new com.forestfire.uav.common.BusinessException(
                        com.forestfire.uav.common.ErrorCode.BAD_REQUEST, "durationHours must be 1..23, got: " + duration);
            }
            closure = new ClosurePlan(true, start, end, startT, duration);
            log.info("Patrol closure ENABLED: {} ~ {}, daily {} ~ {}h", start, end, startT, duration);
        } else {
            closure = new ClosurePlan(false, request.startDate() != null ? request.startDate() : closure.startDate(),
                    request.endDate() != null ? request.endDate() : closure.endDate(),
                    closure.startTime(), closure.durationHours());
            log.info("Patrol closure disabled (normal schedule resumes)");
        }
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

        if (s != null) {
            if (landed) {
                // 落地=班次结束（含人工降落）：开关回位；当日计划班次不再重复起飞
                log.info("Patrol shift finished (origin={}, landed), shift closed", s.origin());
                shift = null;
                return;
            }
            if (!now.isBefore(s.endAt())) {
                // 班次到期：空中下发返航（仅一次），落地后由上面的 landed 分支关闭班次
                if (!s.returning()) {
                    shift = new Shift(s.origin(), s.startedAt(), s.endAt(), true);
                    sendCommand("RETURN_HOME", "patrol shift due");
                }
                return;
            }
            // 手动班次中悬停（GOTO 到位/手动悬停后）：恢复航线巡逻（计划班次不干预）
            if (airborne && isHovering(status) && ORIGIN_MANUAL.equals(s.origin())) {
                sendCommand("RESUME", "manual shift resume wayline");
            }
            return;
        }

        // 无班次：仅在每日计划起飞时刻（±宽限）自动开班一次；错过则次日再飞。
        // 禁期开关的启停只改计划，不在此触发起飞。
        if (landed && atWindowStart(now) && !LocalDate.now(zone).equals(lastAutoStart)) {
            lastAutoStart = LocalDate.now(zone);
            shift = new Shift(ORIGIN_SCHEDULED, now, windowEndOfToday(now), false);
            log.info("Patrol daily auto-start ({}): shift opened, endAt={}",
                    resolvePlan(LocalDate.now(zone)).start(), shift.endAt());
            sendCommand("TAKEOFF", "daily patrol window start");
        } else if (airborne && !inWindow(now)) {
            // 窗口外滞空（含计划切换后）：自动归巢
            sendCommand("RETURN_HOME", "outside patrol window");
        }
    }

    // ---------------- 内部工具 ----------------

    /** 某日期生效的巡逻计划：禁期（启用且日期命中）用禁期窗口，否则用正常窗口 */
    private Plan resolvePlan(LocalDate date) {
        if (isClosureDay(date)) {
            ClosurePlan c = closure;
            return new Plan(c.startTime(), Duration.ofHours(c.durationHours()));
        }
        return new Plan(normalStart, normalDuration);
    }

    private boolean isClosureDay(LocalDate date) {
        ClosurePlan c = closure;
        return c.enabled() && !date.isBefore(c.startDate()) && !date.isAfter(c.endDate());
    }

    /** 当前时刻是否在生效巡逻窗口内（start ≤ t < start+duration，按配置时区） */
    private boolean inWindow(Instant now) {
        LocalDate date = LocalDate.from(now.atZone(zone));
        LocalTime t = LocalTime.from(now.atZone(zone));
        Plan plan = resolvePlan(date);
        LocalTime end = plan.start().plus(plan.duration());
        return !t.isBefore(plan.start()) && t.isBefore(end);
    }

    /** 当前时刻是否处于每日计划起飞时刻附近（窗口开始 ± 宽限期）——每日自动起飞仅在此触发 */
    private boolean atWindowStart(Instant now) {
        LocalTime t = LocalTime.from(now.atZone(zone));
        Plan plan = resolvePlan(LocalDate.from(now.atZone(zone)));
        LocalTime end = plan.start().plusMinutes(AUTO_START_GRACE_MINUTES);
        return !t.isBefore(plan.start()) && t.isBefore(end);
    }

    /** 下一个窗口结束时刻（今天未到则今天，已过则明天；各按当天生效计划）——手动班次的返航端点 */
    private Instant nextWindowEnd(Instant now) {
        ZoneId z = zone;
        LocalDate today = LocalDate.from(now.atZone(z));
        Instant todayEnd = windowEndFor(today);
        if (now.isBefore(todayEnd)) {
            return todayEnd;
        }
        return windowEndFor(today.plusDays(1));
    }

    /** 今日窗口结束时刻（仅在窗口内调用） */
    private Instant windowEndOfToday(Instant now) {
        return windowEndFor(LocalDate.from(now.atZone(zone)));
    }

    private Instant windowEndFor(LocalDate date) {
        Plan plan = resolvePlan(date);
        return date.atTime(plan.start().plus(plan.duration())).atZone(zone).toInstant();
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

    private static LocalTime parseTime(String value, String config) {
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(config + " must be HH:mm, got: " + value, e);
        }
    }

    private static Duration validateDuration(int hours, String config) {
        if (hours < 1 || hours > 23) {
            throw new IllegalArgumentException(config + " must be 1..23, got: " + hours);
        }
        return Duration.ofHours(hours);
    }
}

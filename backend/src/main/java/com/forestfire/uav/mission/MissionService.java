package com.forestfire.uav.mission;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.device.UavDeviceEntity;
import com.forestfire.uav.device.UavDeviceRepository;
import com.forestfire.uav.dispatch.DispatchRecordEntity;
import com.forestfire.uav.dispatch.DispatchRecordRepository;
import com.forestfire.uav.dispatch.DispatchService;
import com.forestfire.uav.fire.FireIncidentEntity;
import com.forestfire.uav.fire.FireIncidentRepository;
import com.forestfire.uav.fire.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 任务服务：
 * <ol>
 *   <li>POST /api/v1/missions — 创建 FIRE_VERIFICATION 任务（mission_no="MIS-"+日期+4位序号，
 *       status=CREATED，target 落 geometry 列，altitude/altitudeMode/requiredCapabilities 落
 *       mission_waypoint.action JSONB）；</li>
 *   <li>GET /api/v1/missions、GET /api/v1/missions/{id} — 任务查询；</li>
 *   <li>POST /api/v1/missions/{id}/start — MVP 调度闭环：DispatchService.dispatch 选机评分落
 *       dispatch_record，mission ASSIGNED→EXECUTING，自动经 CommandService 下发 GOTO。</li>
 * </ol>
 */
@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    /** MissionType 六值枚举（enums.yaml 第1项） */
    private static final Set<String> MISSION_TYPES = Set.of(
            "PATROL", "RISK_PATROL", "FIRE_VERIFICATION", "FIRE_TRACKING",
            "THERMAL_INSPECTION", "SUPPORT");

    /** 可发起 start 的状态（MVP 放宽：审批链未实现） */
    private static final Set<String> STARTABLE_STATUSES = Set.of("CREATED", "PENDING", "APPROVED");

    /** AltitudeMode 三值（05号 defs.schema.json#AltitudeMode） */
    private static final Set<String> ALTITUDE_MODES = Set.of(
            "ELLIPSOID", "RELATIVE_TO_TAKEOFF", "AGL");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final MissionRepository missionRepository;
    private final MissionWaypointRepository waypointRepository;
    private final FireIncidentRepository incidentRepository;
    private final UavDeviceRepository deviceRepository;
    private final DispatchRecordRepository dispatchRecordRepository;
    private final DispatchService dispatchService;

    public MissionService(MissionRepository missionRepository,
                          MissionWaypointRepository waypointRepository,
                          FireIncidentRepository incidentRepository,
                          UavDeviceRepository deviceRepository,
                          DispatchRecordRepository dispatchRecordRepository,
                          DispatchService dispatchService) {
        this.missionRepository = missionRepository;
        this.waypointRepository = waypointRepository;
        this.incidentRepository = incidentRepository;
        this.deviceRepository = deviceRepository;
        this.dispatchRecordRepository = dispatchRecordRepository;
        this.dispatchService = dispatchService;
    }

    // ---------------- 创建 ----------------

    /** 任务创建请求体（07号第15章）：{missionType, incidentId, target{...}, priority, requiredCapabilities[...]} */
    public record MissionCreateRequest(
            String missionType,
            Object incidentId,
            Target target,
            Object priority,
            List<String> requiredCapabilities) {

        /** 目标点（05号 TargetPoint：altitude 与 altitudeMode 必须成对，缺省时应用补默认） */
        public record Target(Double latitude, Double longitude, Double altitude, String altitudeMode) {
        }
    }

    @Transactional
    public MissionViews.MissionView create(MissionCreateRequest request) {
        if (request == null || request.missionType() == null || request.missionType().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "missionType is required");
        }
        String missionType = request.missionType().trim().toUpperCase(Locale.ROOT);
        if (!MISSION_TYPES.contains(missionType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "invalid MissionType: " + request.missionType() + " (allowed: " + MISSION_TYPES + ")");
        }
        if (request.target() == null || request.target().latitude() == null
                || request.target().longitude() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "target.latitude/target.longitude are required");
        }
        UUID incidentId = resolveIncidentId(request.incidentId());
        Instant now = Instant.now();

        MissionEntity mission = new MissionEntity();
        mission.setId(UUID.randomUUID());                               // id UUID PK（应用生成）
        mission.setMissionNo(nextMissionNo(now));                       // mission_no NOT NULL UNIQUE
        mission.setMissionType(missionType);                            // mission_type NOT NULL
        mission.setIncidentId(incidentId);                              // incident_id
        mission.setPriority(normalizePriority(request.priority()));     // priority
        mission.setStatus("CREATED");                                   // status NOT NULL
        mission.setTarget(GeoUtils.toPoint(request.target().latitude(), request.target().longitude())); // target
        mission.setCreatedAt(now);                                      // created_at NOT NULL
        mission.setUpdatedAt(now);                                      // updated_at NOT NULL
        mission = missionRepository.save(mission);

        // 航点：单目标点，altitudeMode/requiredCapabilities 存 action JSONB
        double altitude = request.target().altitude() != null ? request.target().altitude() : 100.0;
        String altitudeMode = request.target().altitudeMode() != null
                && !request.target().altitudeMode().isBlank()
                ? request.target().altitudeMode().trim().toUpperCase(Locale.ROOT)
                : "RELATIVE_TO_TAKEOFF";
        if (!ALTITUDE_MODES.contains(altitudeMode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "invalid altitudeMode: " + request.target().altitudeMode()
                            + " (allowed: " + ALTITUDE_MODES + ")");
        }
        MissionWaypointEntity waypoint = new MissionWaypointEntity();
        waypoint.setId(UUID.randomUUID());                              // id UUID PK（应用生成）
        waypoint.setMissionId(mission.getId());                         // mission_id NOT NULL
        waypoint.setSequenceNo(1);                                      // sequence_no NOT NULL
        waypoint.setLatitude(request.target().latitude());              // latitude NOT NULL
        waypoint.setLongitude(request.target().longitude());            // longitude NOT NULL
        waypoint.setGeometry(mission.getTarget());                      // geometry
        waypoint.setAltitude(altitude);                                 // altitude
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("altitudeMode", altitudeMode);
        action.put("altitude", altitude);
        if (request.requiredCapabilities() != null && !request.requiredCapabilities().isEmpty()) {
            action.put("requiredCapabilities", request.requiredCapabilities());
        }
        waypoint.setAction(action);                                     // action JSONB
        waypoint.setCreatedAt(now);                                     // created_at NOT NULL
        waypointRepository.save(waypoint);

        return getMissionView(mission.getId());
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<MissionViews.MissionView> list() {
        return missionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(m -> toView(m, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public MissionViews.MissionView getMissionView(UUID missionId) {
        MissionEntity mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "mission not found: " + missionId));
        DispatchRecordEntity latestDispatch = dispatchRecordRepository
                .findFirstByMissionIdOrderByDecisionTimeDesc(mission.getId()).orElse(null);
        return toView(mission, latestDispatch);
    }

    // ---------------- start（调度闭环） ----------------

    @Transactional
    public MissionViews.MissionStartResult start(UUID missionId) {
        MissionEntity mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "mission not found: " + missionId));
        String current = mission.getStatus();
        if (!STARTABLE_STATUSES.contains(current)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "mission not startable in status " + current
                            + " (startable: " + STARTABLE_STATUSES + ")");
        }
        MissionWaypointEntity targetWaypoint = waypointRepository
                .findByMissionIdOrderBySequenceNoAsc(mission.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "mission has no waypoint"));

        DispatchService.DispatchOutcome outcome = dispatchService.dispatch(mission, targetWaypoint);

        Instant now = Instant.now();
        mission.setAssignedUavId(outcome.device().getId());             // assigned_uav_id
        // origin：以 UAV 当前位置优先、home 兜底回填（可空）
        mission.setOrigin(GeoUtils.toPoint(
                outcome.device().getCurrentLatitude() != null
                        ? outcome.device().getCurrentLatitude() : outcome.device().getHomeLatitude(),
                outcome.device().getCurrentLongitude() != null
                        ? outcome.device().getCurrentLongitude() : outcome.device().getHomeLongitude()));
        mission.setStatus("ASSIGNED");                                  // 状态机：→ ASSIGNED
        missionRepository.saveAndFlush(mission);
        mission.setStatus("EXECUTING");                                 // 状态机：ASSIGNED → EXECUTING
        mission.setActualStartAt(now);
        mission.setUpdatedAt(now);
        missionRepository.save(mission);

        return new MissionViews.MissionStartResult(
                mission.getId(), mission.getMissionNo(), mission.getStatus(),
                outcome.record().getId(), outcome.device().getDeviceCode(),
                outcome.record().getScore(),
                outcome.command().id(), outcome.command().commandNo());
    }

    // ---------------- 内部工具 ----------------

    private MissionViews.MissionView toView(MissionEntity mission, DispatchRecordEntity dispatch) {
        String incidentNo = null;
        if (mission.getIncidentId() != null) {
            incidentNo = incidentRepository.findById(mission.getIncidentId())
                    .map(FireIncidentEntity::getIncidentNo).orElse(null);
        }
        String assignedUav = null;
        if (mission.getAssignedUavId() != null) {
            assignedUav = deviceRepository.findById(mission.getAssignedUavId())
                    .map(UavDeviceEntity::getDeviceCode).orElse(null);
        }
        List<MissionViews.WaypointView> waypoints = waypointRepository
                .findByMissionIdOrderBySequenceNoAsc(mission.getId()).stream()
                .map(w -> new MissionViews.WaypointView(w.getId(), w.getSequenceNo(),
                        w.getLatitude(), w.getLongitude(), w.getAltitude(), w.getSpeed(),
                        w.getHeading(), w.getAction()))
                .toList();
        Double targetAltitude = null;
        String targetAltitudeMode = null;
        if (!waypoints.isEmpty()) {
            targetAltitude = waypoints.get(0).altitude();
            Object mode = waypoints.get(0).action() == null
                    ? null : waypoints.get(0).action().get("altitudeMode");
            targetAltitudeMode = mode == null ? null : String.valueOf(mode);
        }
        MissionViews.DispatchView dispatchView = dispatch != null
                ? MissionViews.toDispatchView(dispatch, assignedUav) : null;
        return new MissionViews.MissionView(
                mission.getId(), mission.getMissionNo(), mission.getMissionType(),
                mission.getIncidentId(), incidentNo, assignedUav,
                mission.getPriority(), mission.getStatus(),
                mission.getActualStartAt(), mission.getActualEndAt(),
                mission.getTarget() != null ? mission.getTarget().getY() : null,
                mission.getTarget() != null ? mission.getTarget().getX() : null,
                targetAltitude, targetAltitudeMode,
                mission.getCreatedAt(), mission.getUpdatedAt(),
                waypoints, dispatchView);
    }

    /** mission_no 序号："MIS-"+yyyyMMdd(UTC)+"-"+%04d（当日前缀计数+1） */
    private String nextMissionNo(Instant now) {
        String prefix = "MIS-" + DATE_FMT.format(now) + "-";
        long seq = missionRepository.countByMissionNoStartingWith(prefix) + 1;
        return prefix + String.format("%04d", seq);
    }

    /** 宽容解析 incident 引用：UUID → id 查询；否则按 incident_no 查询；未提供返回 null；查不到 40002 */
    private UUID resolveIncidentId(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        Optional<FireIncidentEntity> found;
        try {
            found = incidentRepository.findById(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            found = incidentRepository.findByIncidentNo(text);
        }
        return found.map(FireIncidentEntity::getId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                        "incident not found: " + text));
    }

    /** priority 归一：字符串原样大写；数值 ≥80→HIGH / ≥50→NORMAL / 其余 LOW；缺省 NORMAL */
    private static String normalizePriority(Object priority) {
        if (priority == null) {
            return "NORMAL";
        }
        if (priority instanceof Number n) {
            double v = n.doubleValue();
            if (v >= 80) {
                return "HIGH";
            }
            return v >= 50 ? "NORMAL" : "LOW";
        }
        String text = String.valueOf(priority).trim().toUpperCase(Locale.ROOT);
        return text.isEmpty() ? "NORMAL" : text;
    }
}

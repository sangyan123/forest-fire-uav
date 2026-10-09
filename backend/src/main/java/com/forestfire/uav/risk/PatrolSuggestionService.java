package com.forestfire.uav.risk;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.fire.GeoUtils;
import com.forestfire.uav.media.AiServiceClient;
import com.forestfire.uav.mission.MissionEntity;
import com.forestfire.uav.mission.MissionRepository;
import com.forestfire.uav.mission.MissionService;
import com.forestfire.uav.mission.MissionViews;
import com.forestfire.uav.mission.MissionWaypointEntity;
import com.forestfire.uav.mission.MissionWaypointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 巡检建议服务（F08 一期，03号第91.5节）：
 * <ol>
 *   <li>generate：risk_score 降序取前 N（constants.yaml#patrol_suggestion#top_n_suggestions，
 *       缺省 5；areaIds 可指定），已有 SUGGESTED 的区域去重跳过；网格内蛇形覆盖航点由
 *       ai-service mock 生成（lane 80m/航高 100m/9m/s），patrol_area(status=SUGGESTED)，
 *       航点与估时存 reason JSONB；</li>
 *   <li>dispatch：SUGGESTED → 创建 RISK_PATROL mission（risk_area_id/patrol_area_id 回填，
 *       全部航点写 mission_waypoint），复用 F09-Basic 调度链（MissionService.start →
 *       DispatchService 评分选机 → GOTO 首航点）；patrol_area.status→DISPATCHED，
 *       reason 追加 missionId（03号第91.3节）；</li>
 *   <li>dismiss：SUGGESTED → DISMISSED。</li>
 * </ol>
 */
@Service
public class PatrolSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(PatrolSuggestionService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    /** PatrolSuggestionStatus 四值（enums.yaml） */
    private static final String STATUS_SUGGESTED = "SUGGESTED";
    private static final String STATUS_DISPATCHED = "DISPATCHED";
    private static final String STATUS_DISMISSED = "DISMISSED";

    @Value("${risk.patrol.lane-spacing-m:80}")
    private double laneSpacingM;
    @Value("${risk.patrol.coverage-altitude-m:100}")
    private double coverageAltitudeM;
    @Value("${risk.patrol.cruise-speed-mps:9}")
    private double cruiseSpeedMps;
    @Value("${risk.patrol.top-n:5}")
    private int topN;

    private final RiskAreaRepository areaRepository;
    private final PatrolAreaRepository patrolAreaRepository;
    private final MissionRepository missionRepository;
    private final MissionWaypointRepository waypointRepository;
    private final MissionService missionService;
    private final AiServiceClient aiServiceClient;

    public PatrolSuggestionService(RiskAreaRepository areaRepository,
                                   PatrolAreaRepository patrolAreaRepository,
                                   MissionRepository missionRepository,
                                   MissionWaypointRepository waypointRepository,
                                   MissionService missionService,
                                   AiServiceClient aiServiceClient) {
        this.areaRepository = areaRepository;
        this.patrolAreaRepository = patrolAreaRepository;
        this.missionRepository = missionRepository;
        this.waypointRepository = waypointRepository;
        this.missionService = missionService;
        this.aiServiceClient = aiServiceClient;
    }

    // ---------------- 生成 ----------------

    /** 生成建议请求体：{areaIds?: [uuid]}（缺省=全部按分数取前 N） */
    public record GenerateRequest(List<UUID> areaIds) {
    }

    @Transactional
    public List<RiskViews.SuggestionView> generate(GenerateRequest request) {
        List<UUID> wanted = request == null ? null : request.areaIds();
        List<RiskAreaEntity> candidates = areaRepository.findAllByOrderByRiskScoreDesc().stream()
                .filter(a -> wanted == null || wanted.contains(a.getId()))
                .toList();

        List<RiskViews.SuggestionView> created = new ArrayList<>();
        int generated = 0;
        for (RiskAreaEntity area : candidates) {
            if (generated >= topN) {
                break;
            }
            // 去重：同区域已有待处理建议则跳过
            if (patrolAreaRepository
                    .findFirstByRiskAreaIdAndStatusOrderByGeneratedAtDesc(
                            area.getId(), STATUS_SUGGESTED).isPresent()) {
                continue;
            }
            double[] env = GeoUtils.envelopeLatLon(area.getGeometry());
            if (env == null) {
                continue;
            }
            AiServiceClient.PatrolWaypointsResult route = aiServiceClient.patrolWaypoints(
                    env[0], env[1], env[2], env[3], laneSpacingM, coverageAltitudeM, cruiseSpeedMps);
            if (route.waypoints().isEmpty()) {
                continue;
            }

            Instant now = Instant.now();
            PatrolAreaEntity suggestion = new PatrolAreaEntity();
            suggestion.setId(UUID.randomUUID());
            suggestion.setRiskAreaId(area.getId());
            suggestion.setGeometry(GeoUtils.firstPolygonOf(area.getGeometry()));
            suggestion.setPriority(area.getRiskScore() == null
                    ? null : (int) Math.round(area.getRiskScore()));
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("areaCode", area.getAreaCode());
            reason.put("riskScore", area.getRiskScore());
            reason.put("riskLevel", area.getRiskLevel());
            reason.put("laneSpacingM", laneSpacingM);
            reason.put("estimatedDurationMin", route.estimatedDurationMin());
            reason.put("routeLengthM", route.routeLengthM());
            List<Map<String, Object>> waypointMaps = new ArrayList<>();
            for (AiServiceClient.PatrolWaypoint wp : route.waypoints()) {
                waypointMaps.add(Map.of(
                        "sequenceNo", wp.sequenceNo(),
                        "latitude", wp.latitude(),
                        "longitude", wp.longitude(),
                        "altitude", wp.altitude()));
            }
            reason.put("waypoints", waypointMaps);
            suggestion.setReason(reason);
            suggestion.setGeneratedAt(now);
            suggestion.setStatus(STATUS_SUGGESTED);
            patrolAreaRepository.save(suggestion);
            created.add(toView(suggestion, area));
            generated++;
        }
        log.info("patrol suggestions generated: {} (topN={})", created.size(), topN);
        return created;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<RiskViews.SuggestionView> list(String status) {
        List<PatrolAreaEntity> rows = (status == null || status.isBlank())
                ? patrolAreaRepository.findAllByOrderByGeneratedAtDesc()
                : patrolAreaRepository.findByStatusOrderByGeneratedAtDesc(
                        status.trim().toUpperCase());
        List<RiskViews.SuggestionView> views = new ArrayList<>();
        for (PatrolAreaEntity row : rows) {
            views.add(toView(row, areaRepository.findById(row.getRiskAreaId()).orElse(null)));
        }
        return views;
    }

    // ---------------- 下发 / 驳回 ----------------

    /**
     * 下发：创建 RISK_PATROL mission + 全量航点 → 复用 MissionService.start 调度链
     * （F09-Basic 选机 + GOTO 首航点）→ patrol_area.status=DISPATCHED + reason 记 missionId。
     */
    @Transactional
    public MissionViews.MissionView dispatch(UUID suggestionId) {
        PatrolAreaEntity suggestion = patrolAreaRepository.findById(suggestionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "patrol suggestion not found: " + suggestionId));
        if (!STATUS_SUGGESTED.equals(suggestion.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "suggestion not dispatchable in status " + suggestion.getStatus());
        }
        RiskAreaEntity area = areaRepository.findById(suggestion.getRiskAreaId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                        "risk area not found: " + suggestion.getRiskAreaId()));
        List<AiServiceClient.PatrolWaypoint> waypoints = reasonWaypoints(suggestion);
        if (waypoints.isEmpty()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "suggestion has no waypoints");
        }

        Instant now = Instant.now();
        MissionEntity mission = new MissionEntity();
        mission.setId(UUID.randomUUID());
        mission.setMissionNo(nextMissionNo(now));
        mission.setMissionType("RISK_PATROL");
        mission.setRiskAreaId(area.getId());
        mission.setPatrolAreaId(suggestion.getId());
        mission.setPriority(priorityOf(area.getRiskLevel()));
        mission.setStatus("CREATED");
        AiServiceClient.PatrolWaypoint first = waypoints.get(0);
        mission.setTarget(GeoUtils.toPoint(first.latitude(), first.longitude()));
        mission.setCreatedAt(now);
        mission.setUpdatedAt(now);
        mission = missionRepository.save(mission);

        for (AiServiceClient.PatrolWaypoint wp : waypoints) {
            MissionWaypointEntity entity = new MissionWaypointEntity();
            entity.setId(UUID.randomUUID());
            entity.setMissionId(mission.getId());
            entity.setSequenceNo(wp.sequenceNo());
            entity.setLatitude(wp.latitude());
            entity.setLongitude(wp.longitude());
            entity.setGeometry(GeoUtils.toPoint(wp.latitude(), wp.longitude()));
            entity.setAltitude(wp.altitude());
            entity.setSpeed(cruiseSpeedMps);
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("altitudeMode", "RELATIVE_TO_TAKEOFF");
            action.put("altitude", wp.altitude());
            action.put("source", "F08_PATROL_SUGGESTION");
            entity.setAction(action);
            entity.setCreatedAt(now);
            waypointRepository.save(entity);
        }

        // 复用既有调度闭环：F09-Basic 选机 → dispatch_record → GOTO 首航点 → EXECUTING
        missionService.start(mission.getId());
        MissionViews.MissionView view = missionService.getMissionView(mission.getId());

        suggestion.setStatus(STATUS_DISPATCHED);
        Map<String, Object> reason = new LinkedHashMap<>(
                suggestion.getReason() == null ? Map.of() : suggestion.getReason());
        reason.put("missionId", mission.getId());
        suggestion.setReason(reason);
        patrolAreaRepository.save(suggestion);

        log.info("patrol suggestion {} dispatched as mission {} ({})",
                suggestionId, mission.getMissionNo(), view.status());
        return view;
    }

    @Transactional
    public RiskViews.SuggestionView dismiss(UUID suggestionId) {
        PatrolAreaEntity suggestion = patrolAreaRepository.findById(suggestionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "patrol suggestion not found: " + suggestionId));
        if (!STATUS_SUGGESTED.equals(suggestion.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "suggestion not dismissible in status " + suggestion.getStatus());
        }
        suggestion.setStatus(STATUS_DISMISSED);
        patrolAreaRepository.save(suggestion);
        return toView(suggestion, areaRepository.findById(suggestion.getRiskAreaId()).orElse(null));
    }

    // ---------------- 内部工具 ----------------

    /** reason JSONB waypoints → 有序航点（缺序号按出现顺序补齐） */
    private static List<AiServiceClient.PatrolWaypoint> reasonWaypoints(PatrolAreaEntity suggestion) {
        Object raw = suggestion.getReason() == null ? null : suggestion.getReason().get("waypoints");
        List<AiServiceClient.PatrolWaypoint> waypoints = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object seq = m.get("sequenceNo");
                    Object alt = m.get("altitude");
                    waypoints.add(new AiServiceClient.PatrolWaypoint(
                            seq instanceof Number n ? n.intValue() : waypoints.size() + 1,
                            ((Number) m.get("latitude")).doubleValue(),
                            ((Number) m.get("longitude")).doubleValue(),
                            alt instanceof Number n ? n.doubleValue() : 100.0));
                }
            }
        }
        waypoints.sort(Comparator.comparingInt(AiServiceClient.PatrolWaypoint::sequenceNo));
        return waypoints;
    }

    /** mission_no 序号："MIS-"+yyyyMMdd(UTC)+"-"+%04d（与 MissionService 同规则） */
    private String nextMissionNo(Instant now) {
        String prefix = "MIS-" + DATE_FMT.format(now) + "-";
        long seq = missionRepository.countByMissionNoStartingWith(prefix) + 1;
        return prefix + String.format("%04d", seq);
    }

    /** 风险等级 → mission priority（HIGH→HIGH / MEDIUM→NORMAL / LOW→LOW） */
    private static String priorityOf(String riskLevel) {
        if ("HIGH".equals(riskLevel)) {
            return "HIGH";
        }
        return "MEDIUM".equals(riskLevel) ? "NORMAL" : "LOW";
    }

    private static RiskViews.SuggestionView toView(PatrolAreaEntity suggestion, RiskAreaEntity area) {
        List<RiskViews.WaypointView> waypoints = new ArrayList<>();
        Double duration = null;
        if (suggestion.getReason() != null) {
            Object raw = suggestion.getReason().get("waypoints");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Object seq = m.get("sequenceNo");
                        Object alt = m.get("altitude");
                        waypoints.add(new RiskViews.WaypointView(
                                seq instanceof Number n ? n.intValue() : 0,
                                ((Number) m.get("latitude")).doubleValue(),
                                ((Number) m.get("longitude")).doubleValue(),
                                alt instanceof Number n ? n.doubleValue() : 100.0));
                    }
                }
            }
            waypoints.sort(Comparator.comparingInt(w -> w.sequenceNo() == null ? 0 : w.sequenceNo()));
            Object d = suggestion.getReason().get("estimatedDurationMin");
            if (d instanceof Number n) {
                duration = n.doubleValue();
            }
        }
        Object missionIdRaw = suggestion.getReason() == null
                ? null : suggestion.getReason().get("missionId");
        UUID missionId = null;
        try {
            missionId = missionIdRaw == null ? null : UUID.fromString(String.valueOf(missionIdRaw));
        } catch (IllegalArgumentException ignored) {
            // reason.missionId 非法时忽略（不应发生）
        }
        return new RiskViews.SuggestionView(suggestion.getId(), suggestion.getRiskAreaId(),
                area == null ? null : area.getAreaCode(),
                area == null ? null : area.getRiskScore(),
                GeoUtils.polygonToGeoJson(suggestion.getGeometry()),
                waypoints, suggestion.getPriority(), duration,
                suggestion.getStatus(), missionId, suggestion.getGeneratedAt());
    }
}

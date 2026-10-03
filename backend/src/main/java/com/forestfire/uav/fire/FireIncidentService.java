package com.forestfire.uav.fire;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.device.UavDeviceEntity;
import com.forestfire.uav.device.UavDeviceRepository;
import com.forestfire.uav.media.AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 火情事件服务：
 * <ol>
 *   <li>火情去重/事件登记（100m/120s，config/algorithm/fire-detection-v1.yaml dedup 段）：
 *       先按未关闭状态 + updated_at 120s 窗口（事件最后活动时间，持续采集持续合并）+ 经纬度矩形（±100m 对应经纬度增量）粗筛，
 *       再 Haversine 精算距离 ≤100m；命中则挂接最新火点并刷新 latest 字段，未命中则新建 incident；</li>
 *   <li>事件列表/详情；</li>
 *   <li>PATCH status — IncidentStatus 状态机校验（主线 SUSPECTED→VERIFYING→CONFIRMED→TRACKING→
 *       PROCESSING→RESOLVED→CLOSED；分支 VERIFYING→FALSE_ALARM 终态），非法迁移 40003；</li>
 *   <li>F04 核验 — 调 AI verification，落 fire_verification，按 decision 推移 incident 状态；</li>
 *   <li>火点手动创建/查询（07号第13章）、检测记录手动创建（07号第11章，demo 备用）。</li>
 * </ol>
 */
@Service
public class FireIncidentService {

    private static final Logger log = LoggerFactory.getLogger(FireIncidentService.class);

    /** 去重距离（米）——fire-detection-v1.yaml dedup.distance_m */
    static final double DEDUP_DISTANCE_M = 100.0;

    /** 去重时间窗（秒）——fire-detection-v1.yaml dedup.time_s */
    static final long DEDUP_WINDOW_SECONDS = 120;

    /**
     * 误报抑制窗口（秒）——fire-detection-v1.yaml dedup.false_alarm_suppress_window_s。
     * FALSE_ALARM 终态事件在窗口内、同点（≤去重距离）的后续检测不再新建事件（挂接原事件留痕）。
     * D8 彩排实测缺陷：误报判定后无人机仍在采集，检测因终态事件不参与去重而裂出新 SUSPECTED 事件
     * （INC-0002 判误报后 2 秒同坐标冒出 INC-0003）。取 30 分钟：覆盖演示采集全程，
     * 真实复燃概率低；RESOLVED/CLOSED 不抑制（处置完毕后的复燃属新警情，应当告警）。
     */
    static final long FALSE_ALARM_SUPPRESS_SECONDS = 1800;

    /** T_alert（自动创建 detection 的置信度下限）——fire-detection-v1.yaml thresholds.t_alert */
    public static final double T_ALERT = 0.60;

    /** T_confirm（High Confidence 门槛，用于 incident level）——fire-detection-v1.yaml thresholds.t_confirm */
    static final double T_CONFIRM = 0.80;

    /** IncidentStatus 全集（enums.yaml 第4项，8 值） */
    private static final Set<String> ALL_STATUSES = Set.of(
            "SUSPECTED", "VERIFYING", "CONFIRMED", "FALSE_ALARM",
            "TRACKING", "PROCESSING", "RESOLVED", "CLOSED");

    /** 合法状态迁移表（enums.yaml IncidentStatus.transitions） */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "SUSPECTED", Set.of("VERIFYING"),
            "VERIFYING", Set.of("CONFIRMED", "FALSE_ALARM"),
            "CONFIRMED", Set.of("TRACKING"),
            "TRACKING", Set.of("PROCESSING"),
            "PROCESSING", Set.of("RESOLVED"),
            "RESOLVED", Set.of("CLOSED"),
            "FALSE_ALARM", Set.of(),
            "CLOSED", Set.of());

    /** 去重时排除的终态（"未关闭"事件 = 非 FALSE_ALARM/RESOLVED/CLOSED） */
    private static final Set<String> TERMINAL_STATUSES = Set.of("FALSE_ALARM", "RESOLVED", "CLOSED");

    /** LocationMethod 五值枚举（enums.yaml 第10项） */
    private static final Set<String> LOCATION_METHODS = Set.of(
            "RAY_GROUND_INTERSECTION", "DEM_RAY_INTERSECTION",
            "RTK_GEOREFERENCED", "DIRECT_GEOREFERENCED", "MANUAL");

    /** F04 证据默认分（FIRE 场景基线，D1）：加权 0.35×0.9+0.35×0.92+0.15×0.88+0.15×0.9 ≈ 0.904 ≥0.80 → CONFIRMED */
    private static final Map<String, Double> DEFAULT_EVIDENCE = Map.of(
            "rgb", 0.9, "thermal", 0.92, "temporal", 0.88, "spatial", 0.9);

    /**
     * FALSE_ALARM 场景低证据（D2 误报演示线，media.metadata.scenarioType=FALSE_ALARM 时派生）：
     * 加权 0.35×0.30+0.35×0.22+0.15×0.40+0.15×0.30 ≈ 0.287 <0.50 → FALSE_ALARM。
     * 【MVP mock 模式决策】证据派生自场景提示而非真实算法打分；真实模型上线后由真实证据替代。
     */
    private static final Map<String, Double> FALSE_ALARM_SCENARIO_EVIDENCE = Map.of(
            "rgb", 0.30, "thermal", 0.22, "temporal", 0.40, "spatial", 0.30);

    /** 场景提示枚举（D2 误报演示线）：media.metadata.scenarioType，缺省 FIRE */
    public static final String SCENARIO_FIRE = "FIRE";

    public static final String SCENARIO_FALSE_ALARM = "FALSE_ALARM";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final FireIncidentRepository incidentRepository;
    private final FirePointRepository pointRepository;
    private final FireDetectionRepository detectionRepository;
    private final FireVerificationRepository verificationRepository;
    private final FirePolygonRepository polygonRepository;
    private final UavDeviceRepository deviceRepository;
    private final AiServiceClient aiServiceClient;

    public FireIncidentService(FireIncidentRepository incidentRepository,
                               FirePointRepository pointRepository,
                               FireDetectionRepository detectionRepository,
                               FireVerificationRepository verificationRepository,
                               FirePolygonRepository polygonRepository,
                               UavDeviceRepository deviceRepository,
                               AiServiceClient aiServiceClient) {
        this.incidentRepository = incidentRepository;
        this.pointRepository = pointRepository;
        this.detectionRepository = detectionRepository;
        this.verificationRepository = verificationRepository;
        this.polygonRepository = polygonRepository;
        this.deviceRepository = deviceRepository;
        this.aiServiceClient = aiServiceClient;
    }

    // ---------------- 1. 去重 / 事件登记 ----------------

    /** 去重挂接结果 */
    public record IncidentAttachResult(FireIncidentEntity incident, boolean merged) {
    }

    /**
     * 火情去重（100m/120s）：为给定火点寻找可归属的未关闭事件。
     * 粗筛（状态非终态 + updated_at 在 120s 窗口 + 经纬度矩形）后 Haversine 精算 ≤100m。
     * 命中：更新 incident latest 相关字段（坐标/extra.latestConfidence/detectionCount，刷新 updated_at）；
     * 未命中：新建 incident（status=SUSPECTED，level 按 confidence≥0.80→HIGH 否则 MEDIUM）。
     * 两种路径都回填 fire_point.incident_id。
     *
     * <p>时间窗用 updated_at（最后活动时间）而非 first_detected_at：持续采集的同一火情首次检测
     * 可能早于 120s，用 first_detected_at 会把同一火情重复拆成多个事件（D6 彩排实测复现，
     * 火情场景恢复采集后同坐标生成了第二个 SUSPECTED 事件）。</p>
     *
     * <p>D2 误报演示线：scenarioType（media.metadata.scenarioType，FIRE/FALSE_ALARM，缺省 FIRE）
     * 在创建与合并两条路径都写入 incident.extra.scenarioType（后到覆盖，演示线空间上分离，
     * 实际不会互相覆盖），核验时据此派生证据。</p>
     *
     * <p>误报抑制（D8）：去重候选排除终态事件，误报判定后同点持续采集会裂出新 SUSPECTED 事件；
     * 新建前先查 FALSE_ALARM 抑制窗口（{@link #FALSE_ALARM_SUPPRESS_SECONDS}）内的同点误报事件，
     * 命中则检测挂接原事件留痕、不新建。</p>
     */
    @Transactional
    public IncidentAttachResult attachPointToIncident(FirePointEntity point, BigDecimal confidence,
                                                      String scenarioType) {
        double lat = point.getLatitude();
        double lon = point.getLongitude();
        Instant detectedAt = point.getDetectedAt();
        Instant now = Instant.now();

        // 经纬度矩形增量（100m）：纬度 1°≈111_320m；经度按纬度余弦收缩
        double dLat = DEDUP_DISTANCE_M / 111_320.0;
        double dLon = DEDUP_DISTANCE_M
                / (111_320.0 * Math.max(Math.cos(Math.toRadians(lat)), 0.01));

        List<FireIncidentEntity> candidates = incidentRepository
                .findByStatusNotInAndUpdatedAtAfterAndLatitudeBetweenAndLongitudeBetween(
                        TERMINAL_STATUSES,
                        detectedAt.minus(Duration.ofSeconds(DEDUP_WINDOW_SECONDS)),
                        lat - dLat, lat + dLat,
                        lon - dLon, lon + dLon);

        FireIncidentEntity matched = null;
        double matchedDistance = Double.MAX_VALUE;
        for (FireIncidentEntity c : candidates) {
            if (c.getLatitude() == null || c.getLongitude() == null) {
                continue;
            }
            double d = GeoUtils.haversineMeters(lat, lon, c.getLatitude(), c.getLongitude());
            if (d <= DEDUP_DISTANCE_M && d < matchedDistance) {
                matchedDistance = d;
                matched = c;
            }
        }

        FireIncidentEntity incident;
        boolean merged;
        if (matched != null) {
            merged = true;
            incident = matched;
            // latest 相关字段：坐标随最新火点；first_detected_at 保留最早值
            incident.setLatitude(lat);
            incident.setLongitude(lon);
            incident.setGeometry(point.getGeometry());
            Map<String, Object> extra = extraOf(incident);
            int previousCount = extra.get("detectionCount") instanceof Number n ? n.intValue() + 1 : 2;
            extra.put("detectionCount", previousCount);
            extra.put("latestConfidence", confidence);
            double previousMax = extra.get("maxConfidence") instanceof Number n2
                    ? n2.doubleValue() : (confidence == null ? 0 : confidence.doubleValue());
            extra.put("maxConfidence", confidence != null
                    ? Math.max(previousMax, confidence.doubleValue()) : previousMax);
            extra.put("lastMergedPointId", point.getId().toString());
            extra.put("lastMergeDistanceM", Math.round(matchedDistance * 10) / 10.0);
            extra.put("scenarioType", scenarioType);                    // D2：合并路径也写入场景提示
            incident.setExtra(extra);
            incident.setUpdatedAt(now);
            incident = incidentRepository.save(incident);
            log.debug("dedup hit: point {} merged into incident {} ({}m)",
                    point.getId(), incident.getIncidentNo(), Math.round(matchedDistance));
        } else {
            // 误报抑制：FALSE_ALARM 终态不参与去重（终态被候选查询排除），误报判定后若仍在
            // 同点持续采集，每个后续检测都会裂出新 SUSPECTED 事件（D8 彩排实测）。窗口内同点
            // 检测挂接原误报事件留痕（fire_point.incident_id 回填），不再新建事件。
            FireIncidentEntity suppressed = findFalseAlarmSuppression(lat, lon, dLat, dLon, detectedAt);
            if (suppressed != null) {
                merged = false;
                incident = suppressed;
                log.info("false-alarm suppression: point {} within dedup distance of FALSE_ALARM "
                                + "incident {} (verdict within {}s), skip new incident",
                        point.getId(), suppressed.getIncidentNo(), FALSE_ALARM_SUPPRESS_SECONDS);
            } else {
                merged = false;
                incident = new FireIncidentEntity();
                incident.setId(UUID.randomUUID());                      // id UUID PK（应用生成）
                incident.setIncidentNo(nextIncidentNo(now));                // incident_no NOT NULL UNIQUE
                incident.setTitle("无人机巡检发现疑似火情");                  // title
                incident.setStatus("SUSPECTED");                            // status NOT NULL
                incident.setLevel(confidence != null && confidence.doubleValue() >= T_CONFIRM
                        ? "HIGH" : "MEDIUM");                               // level（priority 语义）
                incident.setLatitude(lat);                                  // latitude
                incident.setLongitude(lon);                                 // longitude
                incident.setGeometry(point.getGeometry());                  // geometry
                incident.setFirstDetectedAt(detectedAt);                    // first_detected_at
                incident.setSourceUavId(point.getSourceUavId());            // source_uav_id
                incident.setSourceDetectionId(point.getDetectionId());      // source_detection_id
                incident.setFalseAlarm(false);                              // false_alarm
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("detectionCount", 1);
                extra.put("latestConfidence", confidence);
                extra.put("maxConfidence", confidence);
                extra.put("scenarioType", scenarioType);                    // D2：创建路径写入场景提示
                incident.setExtra(extra);
                recordStatusChange(incident, "SUSPECTED", now);             // D4：初始状态入历史
                incident.setCreatedAt(now);                                 // created_at NOT NULL
                incident.setUpdatedAt(now);                                 // updated_at NOT NULL
                incident = incidentRepository.save(incident);
                log.debug("dedup miss: new incident {} for point {}",
                        incident.getIncidentNo(), point.getId());
            }
        }

        point.setIncidentId(incident.getId());
        pointRepository.save(point);
        return new IncidentAttachResult(incident, merged);
    }

    /**
     * 误报抑制命中查找：窗口内（FALSE_ALARM 事件的 updated_at 即判定落库时间），
     * 经纬度矩形粗筛后 Haversine ≤去重距离的最近误报事件；未命中返回 null。
     * 仅对 FALSE_ALARM 生效——RESOLVED/CLOSED 后同点复燃属新警情，不应抑制。
     */
    private FireIncidentEntity findFalseAlarmSuppression(double lat, double lon,
                                                         double dLat, double dLon,
                                                         Instant detectedAt) {
        List<FireIncidentEntity> candidates = incidentRepository
                .findByStatusAndUpdatedAtAfterAndLatitudeBetweenAndLongitudeBetween(
                        "FALSE_ALARM",
                        detectedAt.minus(Duration.ofSeconds(FALSE_ALARM_SUPPRESS_SECONDS)),
                        lat - dLat, lat + dLat,
                        lon - dLon, lon + dLon);
        FireIncidentEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (FireIncidentEntity c : candidates) {
            if (c.getLatitude() == null || c.getLongitude() == null) {
                continue;
            }
            double d = GeoUtils.haversineMeters(lat, lon, c.getLatitude(), c.getLongitude());
            if (d <= DEDUP_DISTANCE_M && d < nearestDistance) {
                nearestDistance = d;
                nearest = c;
            }
        }
        return nearest;
    }

    // ---------------- 2. 列表 / 详情 ----------------

    @Transactional(readOnly = true)
    public List<FireViews.IncidentSummary> list() {
        return incidentRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public FireViews.IncidentDetail getDetail(UUID incidentId) {
        FireIncidentEntity incident = findIncident(incidentId);
        List<FireViews.PointView> points = pointRepository
                .findByIncidentIdOrderByDetectedAtDesc(incident.getId()).stream()
                .map(FireIncidentService::toPointView)
                .toList();

        // detections：经 fire_point.detection_id 传递关联（fire_detection 无 incident_id 列）
        List<UUID> detectionIds = points.stream()
                .map(FireViews.PointView::detectionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<FireViews.DetectionView> detections = detectionIds.isEmpty()
                ? List.of()
                : detectionRepository.findAllById(detectionIds).stream()
                        .sorted((a, b) -> b.getDetectionTime().compareTo(a.getDetectionTime()))
                        .map(FireIncidentService::toDetectionView)
                        .toList();

        FireViews.VerificationView latest = verificationRepository
                .findFirstByIncidentIdOrderByCreatedAtDesc(incident.getId())
                .map(v -> toVerificationView(v, scenarioTypeOf(incident)))
                .orElse(null);

        return new FireViews.IncidentDetail(
                incident.getId(), incident.getIncidentNo(), incident.getTitle(),
                incident.getStatus(), incident.getLevel(), scenarioTypeOf(incident),
                incident.getLatitude(), incident.getLongitude(),
                incident.getFirstDetectedAt(), incident.getConfirmedAt(), incident.getResolvedAt(),
                incident.getVerificationStatus(), incident.getFalseAlarm(), incident.getDescription(),
                incident.getExtra(), incident.getCreatedAt(), incident.getUpdatedAt(),
                (int) polygonRepository.countByIncidentId(incident.getId()),
                points, detections, latest,
                statusHistoryOf(incident, 0));
    }

    // ---------------- 3. PATCH status（状态机） ----------------

    /** 状态迁移请求体：{status, reason?} */
    public record StatusUpdateRequest(String status, String reason) {
    }

    @Transactional
    public FireViews.IncidentDetail updateStatus(UUID incidentId, StatusUpdateRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "status is required");
        }
        String to = request.status().trim().toUpperCase(Locale.ROOT);
        if (!ALL_STATUSES.contains(to)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "invalid IncidentStatus: " + request.status() + " (allowed: " + ALL_STATUSES + ")");
        }
        FireIncidentEntity incident = findIncident(incidentId);
        String from = incident.getStatus();
        Set<String> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "illegal incident status transition: " + from + " -> " + to
                            + (allowed.isEmpty() ? " (terminal state)" : " (allowed: " + allowed + ")"));
        }

        Instant now = Instant.now();
        incident.setStatus(to);
        if ("CONFIRMED".equals(to) && incident.getConfirmedAt() == null) {
            incident.setConfirmedAt(now);
        }
        if ("RESOLVED".equals(to)) {
            incident.setResolvedAt(now);
        }
        if ("FALSE_ALARM".equals(to)) {
            incident.setFalseAlarm(true);
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            Map<String, Object> extra = extraOf(incident);
            extra.put("lastStatusReason", request.reason());
            extra.put("lastStatusAt", now.toString());
            incident.setExtra(extra);
        }
        recordStatusChange(incident, to, now);                          // D4：合法迁移入历史
        incident.setUpdatedAt(now);
        incidentRepository.save(incident);
        return getDetail(incidentId);
    }

    // ---------------- 4. F04 核验 ----------------

    /**
     * 核验请求体：{uavId?, missionId?, evidence:{rgb,thermal,temporal,spatial}}。
     * D2：evidence 缺省（null/空对象）时按 incident.extra.scenarioType 派生
     * （FIRE→高证据，FALSE_ALARM→低证据）；显式提供时优先于派生（D1 行为）。
     */
    public record VerificationRequest(String uavId, Object missionId, Map<String, Object> evidence) {
    }

    @Transactional
    public FireViews.VerificationView verify(UUID incidentId, VerificationRequest request) {
        FireIncidentEntity incident = findIncident(incidentId);
        String current = incident.getStatus();
        if ("SUSPECTED".equals(current)) {
            // SUSPECTED 不直接跳 CONFIRMED/FALSE_ALARM：先推进合法一步 VERIFYING，再按结论落地
            incident.setStatus("VERIFYING");
            recordStatusChange(incident, "VERIFYING", Instant.now());   // D4：核验推进入历史
        } else if (!"VERIFYING".equals(current)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "verification only allowed from SUSPECTED/VERIFYING, current=" + current);
        }

        // ---- 证据派生（D2 误报演示线）----
        // body 显式提供 evidence（非空）→ 优先用请求值（保持 D1 行为，缺项补默认高证据）；
        // body 未提供 → 按 incident.extra.scenarioType 派生：FIRE（或缺省）高证据（≈0.904→CONFIRMED），
        // FALSE_ALARM 低证据（≈0.287<0.50→FALSE_ALARM）。
        // 【MVP mock 模式决策】派生证据来自场景提示而非真实算法打分；阈值判定仍全部交
        // AI Service（backend 不自行判阈值）；真实模型上线后由真实证据替代。
        String scenarioType = scenarioTypeOf(incident);
        boolean explicitEvidence = request != null && request.evidence() != null
                && !request.evidence().isEmpty();
        Map<String, Object> evidence = new LinkedHashMap<>();
        String evidenceSource;
        if (explicitEvidence) {
            evidenceSource = "REQUEST";
            for (Map.Entry<String, Double> e : DEFAULT_EVIDENCE.entrySet()) {
                evidence.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Object> e : request.evidence().entrySet()) {
                if (e.getValue() instanceof Number n) {
                    evidence.put(e.getKey(), n.doubleValue());
                }
            }
        } else {
            evidenceSource = "SCENARIO_DERIVED";
            Map<String, Double> base = SCENARIO_FALSE_ALARM.equals(scenarioType)
                    ? FALSE_ALARM_SCENARIO_EVIDENCE : DEFAULT_EVIDENCE;
            for (Map.Entry<String, Double> e : base.entrySet()) {
                evidence.put(e.getKey(), e.getValue());
            }
        }

        UUID taskId = UUID.randomUUID();
        AiServiceClient.VerificationResult ai = aiServiceClient.verify(
                taskId, incident.getId().toString(), evidence);
        String decision = ai.decision() == null ? null : ai.decision().trim().toUpperCase(Locale.ROOT);
        if (decision == null || !Set.of("CONFIRMED", "FALSE_ALARM", "UNCERTAIN").contains(decision)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "AI verification returned unexpected decision: " + ai.decision());
        }

        Instant now = Instant.now();
        // 分项成绩以 AI 返回 evidence 为准（缺项回退请求值/默认值）
        Map<String, Double> ev = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : evidence.entrySet()) {
            if (e.getValue() instanceof Number n) {
                ev.put(e.getKey(), n.doubleValue());
            }
        }
        if (ai.evidence() != null) {
            ev.putAll(ai.evidence());
        }
        FireVerificationEntity v = new FireVerificationEntity();
        v.setId(UUID.randomUUID());                                     // id UUID PK（应用生成）
        v.setIncidentId(incident.getId());                              // incident_id NOT NULL
        v.setMissionId(parseUuidOrNull(request == null ? null : request.missionId())); // mission_id
        v.setVerificationType("AI");                                    // verification_type
        v.setRgbScore(bigDecimalOf(ev, "rgb"));                         // rgb_score
        v.setThermalScore(bigDecimalOf(ev, "thermal"));                 // thermal_score
        v.setTemporalScore(bigDecimalOf(ev, "temporal"));               // temporal_score
        v.setSpatialScore(bigDecimalOf(ev, "spatial"));                 // spatial_score
        v.setFinalScore(ai.confidence() != null
                ? BigDecimal.valueOf(ai.confidence()) : null);          // final_score
        v.setResult(decision);                                          // result
        v.setVerifierType("AI_SERVICE");                                // verifier_type
        v.setVerifiedAt(now);                                           // verified_at
        v.setRemark("taskId=" + taskId + "; weights=0.35/0.35/0.15/0.15"
                + "; evidenceSource=" + evidenceSource + "; scenarioType=" + scenarioType
                + "; MVP mock 模式：证据派生自场景提示，真实模型上线后由真实证据替代"); // remark
        v.setCreatedAt(now);                                            // created_at NOT NULL
        verificationRepository.save(v);

        // 按 decision 推移 incident 状态（SUSPECTED 已先推进 VERIFYING，两步均合法）
        incident.setVerificationStatus(decision);
        if ("CONFIRMED".equals(decision)) {
            incident.setStatus("CONFIRMED");
            if (incident.getConfirmedAt() == null) {
                incident.setConfirmedAt(now);
            }
            recordStatusChange(incident, "CONFIRMED", now);             // D4：核验结论入历史
        } else if ("FALSE_ALARM".equals(decision)) {
            incident.setStatus("FALSE_ALARM");
            incident.setFalseAlarm(true);
            recordStatusChange(incident, "FALSE_ALARM", now);           // D4：核验结论入历史
        }
        // UNCERTAIN → 保持 VERIFYING（需人工复核，状态不变故不追加历史）
        incident.setUpdatedAt(now);
        incidentRepository.save(incident);

        return toVerificationView(v, incident.getStatus(), scenarioType, evidenceSource);
    }

    // ---------------- 5. 火点 / 检测手动接口 ----------------

    /** 火点创建请求体（07号第13章）：{incidentId, latitude, longitude, altitude, locationMethod, accuracy} */
    public record FirePointCreateRequest(Object incidentId, String uavId,
                                         Double latitude, Double longitude, Double altitude,
                                         String locationMethod, Double accuracy,
                                         Double confidence, String detectedAt) {
    }

    @Transactional
    public FireViews.PointView createFirePoint(FirePointCreateRequest request) {
        if (request == null
                || request.latitude() == null || request.longitude() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "latitude/longitude are required");
        }
        UUID incidentId = resolveIncidentId(request.incidentId());
        String method = request.locationMethod() == null || request.locationMethod().isBlank()
                ? "MANUAL"
                : request.locationMethod().trim().toUpperCase(Locale.ROOT);
        if (!LOCATION_METHODS.contains(method)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "invalid LocationMethod: " + request.locationMethod() + " (allowed: " + LOCATION_METHODS + ")");
        }
        UUID uavDeviceId = resolveUavDeviceId(request.uavId(), false);
        Instant now = Instant.now();

        FirePointEntity p = new FirePointEntity();
        p.setId(UUID.randomUUID());                                     // id UUID PK（应用生成）
        p.setIncidentId(incidentId);                                    // incident_id
        p.setDetectionId(null);                                         // detection_id（手动创建无检测）
        p.setLatitude(request.latitude());                              // latitude NOT NULL
        p.setLongitude(request.longitude());                            // longitude NOT NULL
        p.setAltitude(request.altitude());                              // altitude
        p.setGeometry(GeoUtils.toPoint(request.latitude(), request.longitude())); // geometry NOT NULL
        p.setPositionErrorRadius(request.accuracy());                   // position_error_radius
        p.setLocationMethod(method);                                    // location_method NOT NULL
        p.setConfidence(decimalOrNull(request.confidence()));           // confidence
        p.setSourceUavId(uavDeviceId);                                  // source_uav_id
        p.setDetectedAt(parseInstant(request.detectedAt(), now));       // detected_at NOT NULL
        p.setCreatedAt(now);                                            // created_at NOT NULL
        p = pointRepository.save(p);

        if (incidentId != null) {
            // 最新火点位置同步到 incident（演示地图跟随）
            FireIncidentEntity incident = findIncident(incidentId);
            incident.setLatitude(request.latitude());
            incident.setLongitude(request.longitude());
            incident.setGeometry(p.getGeometry());
            Map<String, Object> extra = extraOf(incident);
            extra.put("latestPointId", p.getId().toString());
            incident.setExtra(extra);
            incident.setUpdatedAt(now);
            incidentRepository.save(incident);
        }
        return toPointView(p);
    }

    @Transactional(readOnly = true)
    public FireViews.PointView getFirePoint(UUID pointId) {
        return toPointView(pointRepository.findById(pointId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "fire point not found: " + pointId)));
    }

    /** 手动检测创建请求体（07号第11.1章）：{uavId, mediaId, algorithmTaskId, detectionType, confidence, bbox} */
    public record ManualDetectionRequest(String uavId, String mediaId, String algorithmTaskId,
                                         String detectionType, Double confidence, Object bbox) {
    }

    @Transactional
    public FireViews.DetectionView createDetection(ManualDetectionRequest request) {
        if (request == null || request.detectionType() == null || request.detectionType().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "detectionType is required");
        }
        UUID uavDeviceId = resolveUavDeviceId(request.uavId(), false);
        Instant now = Instant.now();

        FireDetectionEntity d = new FireDetectionEntity();
        d.setId(UUID.randomUUID());                                     // id UUID PK（应用生成）
        d.setAlgorithmResultId(parseUuidOrNull(request.algorithmTaskId())); // algorithm_result_id（宽容解析）
        d.setUavId(uavDeviceId);                                        // uav_id
        d.setMediaId(parseUuidOrNull(request.mediaId()));               // media_id（协议编码非 UUID 时为 null）
        d.setDetectionType(request.detectionType().trim().toUpperCase(Locale.ROOT)); // NOT NULL
        d.setConfidence(decimalOrNull(request.confidence()));           // confidence
        d.setBbox(bboxToMap(request.bbox()));                           // bbox JSONB
        d.setDetectionTime(now);                                        // detection_time NOT NULL
        d.setTemporalConfirmed(false);                                  // temporal_confirmed
        d.setCreatedAt(now);                                            // created_at NOT NULL
        d = detectionRepository.save(d);
        return toDetectionView(d);
    }

    // ---------------- 内部工具 ----------------

    private FireIncidentEntity findIncident(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "incident not found: " + incidentId));
    }

    /** incident_no 序号："INC-"+yyyyMMdd(UTC)+"-"+%04d（当日前缀计数+1） */
    private String nextIncidentNo(Instant now) {
        String prefix = "INC-" + DATE_FMT.format(now) + "-";
        long seq = incidentRepository.countByIncidentNoStartingWith(prefix) + 1;
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

    /** 宽容解析 uavId（device_code 或 UUID）；notFound=true 时查不到抛 40002 */
    private UUID resolveUavDeviceId(String uavId, boolean notFound) {
        if (uavId == null || uavId.isBlank()) {
            return null;
        }
        String text = uavId.trim();
        Optional<UavDeviceEntity> found = deviceRepository.findByDeviceCode(text);
        if (found.isEmpty()) {
            try {
                found = deviceRepository.findById(UUID.fromString(text));
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        if (found.isPresent()) {
            return found.get().getId();
        }
        if (notFound) {
            throw new BusinessException(ErrorCode.BUSINESS_NOT_FOUND, "uav not found: " + uavId);
        }
        return null;
    }

    private static Map<String, Object> extraOf(FireIncidentEntity incident) {
        return incident.getExtra() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(incident.getExtra());
    }

    /** extra.scenarioType（D2 误报演示线）；缺失按 FIRE（历史数据语义即 FIRE 线） */
    static String scenarioTypeOf(FireIncidentEntity incident) {
        Object v = incident.getExtra() == null ? null : incident.getExtra().get("scenarioType");
        return v instanceof String s && !s.isBlank() ? s : SCENARIO_FIRE;
    }

    // ---------------- D4：状态历史（fire_incident.extra.statusHistory） ----------------

    /** 列表视图的状态历史条数上限（省流量；详情给全量） */
    private static final int SUMMARY_HISTORY_LIMIT = 5;

    /**
     * 状态变更统一入口：向 extra.statusHistory 追加 {"status":..,"at":<UTC ISO>}。
     * 合并写入（extraOf 复制既有 map），scenarioType/detectionCount 等字段全部保留；
     * 与末条同状态时不重复追加。所有状态变更路径（创建初始 SUSPECTED、PATCH 合法迁移、
     * verification 触发的迁移）都必须经过本方法；非法迁移在各自入口先抛 40003，不会走到这里。
     */
    private static void recordStatusChange(FireIncidentEntity incident, String status, Instant at) {
        Map<String, Object> extra = extraOf(incident);
        List<Object> history = extra.get("statusHistory") instanceof List<?> l
                ? new ArrayList<>(l) : new ArrayList<>();
        String last = history.isEmpty() ? null
                : historyStatusOf(history.get(history.size() - 1));
        if (status.equals(last)) {
            return; // 同状态重复不追加
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", status);
        entry.put("at", at.toString()); // Instant.toString() 即 UTC ISO-8601
        history.add(entry);
        extra.put("statusHistory", history);
        incident.setExtra(extra);
    }

    /** 历史条目的状态值（兼容对象 {status,at} 与字符串两种形态） */
    private static String historyStatusOf(Object entry) {
        if (entry instanceof Map<?, ?> m && m.get("status") != null) {
            return String.valueOf(m.get("status"));
        }
        if (entry instanceof String s) {
            return s;
        }
        return null;
    }

    /** extra.statusHistory → 视图条目列表；limit>0 时只取最近 limit 条（详情传 0 表示全量） */
    private static List<FireViews.StatusHistoryEntry> statusHistoryOf(FireIncidentEntity incident, int limit) {
        Object raw = incident.getExtra() == null ? null : incident.getExtra().get("statusHistory");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<FireViews.StatusHistoryEntry> all = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof Map<?, ?> m) {
                Object at = m.get("at");
                all.add(new FireViews.StatusHistoryEntry(
                        m.get("status") == null ? null : String.valueOf(m.get("status")),
                        at == null ? null : String.valueOf(at)));
            } else if (e instanceof String s) {
                all.add(new FireViews.StatusHistoryEntry(s, null));
            }
        }
        if (limit > 0 && all.size() > limit) {
            return all.subList(all.size() - limit, all.size());
        }
        return all;
    }

    /** media.metadata.scenarioType 宽松归一：缺省/未知 → FIRE（media/ 包复用） */
    public static String normalizeScenarioType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SCENARIO_FIRE;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return SCENARIO_FALSE_ALARM.equals(v) ? SCENARIO_FALSE_ALARM : SCENARIO_FIRE;
    }

    /** bbox 宽松归一：对象 {x,y,width|w,height|h} 或数组 [x,y,w,h] → Map {x,y,width,height}（media/ 包复用） */
    public static Map<String, Object> bboxToMap(Object bbox) {
        if (bbox == null) {
            return null;
        }
        if (bbox instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            alias(out, "w", "width");
            alias(out, "h", "height");
            return out;
        }
        if (bbox instanceof List<?> l) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (l.size() >= 4) {
                out.put("x", l.get(0));
                out.put("y", l.get(1));
                out.put("width", l.get(2));
                out.put("height", l.get(3));
            } else {
                for (int i = 0; i < l.size(); i++) {
                    out.put(String.valueOf(i), l.get(i));
                }
            }
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw", String.valueOf(bbox));
        return out;
    }

    private static void alias(Map<String, Object> map, String from, String to) {
        if (map.containsKey(from) && !map.containsKey(to)) {
            map.put(to, map.remove(from));
        }
    }

    private static Double doubleOrNull(Map<String, Double> map, String key) {
        Double v = map.get(key);
        return v == null ? null : v;
    }

    private static BigDecimal bigDecimalOf(Map<String, Double> map, String key) {
        Double v = doubleOrNull(map, key);
        return v == null ? null : BigDecimal.valueOf(v);
    }

    private static BigDecimal decimalOrNull(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    /** 宽容 UUID 解析：非法（如 "ALG-001"/"MEDIA-001"）返回 null */
    private static UUID parseUuidOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseInstant(String text, Instant fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private FireViews.IncidentSummary toSummary(FireIncidentEntity incident) {
        Map<String, Object> extra = incident.getExtra();
        BigDecimal latestConfidence = numberFromExtra(extra, "latestConfidence");
        Integer detectionCount = numberFromExtra(extra, "detectionCount") == null
                ? null : numberFromExtra(extra, "detectionCount").intValue();
        return new FireViews.IncidentSummary(
                incident.getId(), incident.getIncidentNo(), incident.getTitle(),
                incident.getStatus(), incident.getLevel(), scenarioTypeOf(incident),
                incident.getLatitude(), incident.getLongitude(),
                latestConfidence, detectionCount,
                incident.getFirstDetectedAt(), incident.getVerificationStatus(),
                statusHistoryOf(incident, SUMMARY_HISTORY_LIMIT),
                incident.getCreatedAt(), incident.getUpdatedAt());
    }

    private static BigDecimal numberFromExtra(Map<String, Object> extra, String key) {
        if (extra == null) {
            return null;
        }
        return extra.get(key) instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }

    private static FireViews.PointView toPointView(FirePointEntity p) {
        return new FireViews.PointView(
                p.getId(), p.getIncidentId(), p.getDetectionId(),
                p.getLatitude(), p.getLongitude(), p.getAltitude(),
                p.getPositionErrorRadius(), p.getLocationMethod(), p.getConfidence(),
                p.getSourceUavId(), p.getDetectedAt(), p.getCreatedAt());
    }

    private static FireViews.DetectionView toDetectionView(FireDetectionEntity d) {
        return new FireViews.DetectionView(
                d.getId(), d.getDetectionType(), d.getConfidence(), d.getBbox(),
                d.getLatitude(), d.getLongitude(), d.getMediaId(), d.getDetectionTime());
    }

    private static FireViews.VerificationView toVerificationView(FireVerificationEntity v,
                                                                 String scenarioType) {
        return toVerificationView(v, null, scenarioType, null);
    }

    private static FireViews.VerificationView toVerificationView(FireVerificationEntity v,
                                                                 String incidentStatus,
                                                                 String scenarioType,
                                                                 String evidenceSource) {
        return new FireViews.VerificationView(
                v.getId(), v.getIncidentId(), v.getResult(), v.getFinalScore(),
                v.getRgbScore(), v.getThermalScore(), v.getTemporalScore(), v.getSpatialScore(),
                incidentStatus, v.getVerifiedAt(), scenarioType, evidenceSource);
    }
}

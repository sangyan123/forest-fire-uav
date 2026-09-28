package com.forestfire.uav.dispatch;

import com.forestfire.uav.command.CommandService;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.device.UavDeviceEntity;
import com.forestfire.uav.device.UavDeviceRepository;
import com.forestfire.uav.fire.GeoUtils;
import com.forestfire.uav.mission.MissionEntity;
import com.forestfire.uav.mission.MissionWaypointEntity;
import com.forestfire.uav.telemetry.UavTelemetryEntity;
import com.forestfire.uav.telemetry.UavTelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 调度服务（F09-Basic MVP 版）：
 * <ol>
 *   <li>候选 UAV：uav_device.device_status ∈ {ONLINE, IDLE, AIRBORNE} 且未删除，
 *       最新遥测电量（无遥测回退 uav_device.battery_percent 快照）&gt; 25%；</li>
 *   <li>加权评分 0~100：距离 0.4 + 电量 0.3 + 能力 0.2 + 通信 0.1（factors JSONB 留存可解释依据）；</li>
 *   <li>选机：候选含 "UAV-001" 时直接选它（demo 基线），否则取最高分；</li>
 *   <li>落 dispatch_record（score/factors/estimated_distance/estimated_arrival_seconds）；</li>
 *   <li>经既有 CommandService 下发 GOTO（target 坐标 + altitude/altitudeMode，走 gateway）。</li>
 * </ol>
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    /** 可调度设备状态（任务书口径：ONLINE/IDLE/AIRBORNE） */
    private static final Set<String> DISPATCHABLE_STATUSES = Set.of("ONLINE", "IDLE", "AIRBORNE");

    /** 最低电量（%） */
    private static final double MIN_BATTERY_PERCENT = 25.0;

    /** 优先选中的演示基线机型 */
    private static final String PREFERRED_DEVICE_CODE = "UAV-001";

    /** 距离归一化参考（米）：达到即 0 分 */
    private static final double REF_DISTANCE_M = 5_000.0;

    /** 巡航速度假设（m/s），到达时间估算用 */
    private static final double CRUISE_SPEED_MPS = 10.0;

    /** 权重：距离/电量/能力/通信（合计 1.0） */
    private static final double W_DISTANCE = 0.4;
    private static final double W_BATTERY = 0.3;
    private static final double W_CAPABILITY = 0.2;
    private static final double W_COMMUNICATION = 0.1;

    private final UavDeviceRepository deviceRepository;
    private final UavTelemetryRepository telemetryRepository;
    private final DispatchRecordRepository dispatchRecordRepository;
    private final CommandService commandService;

    public DispatchService(UavDeviceRepository deviceRepository,
                           UavTelemetryRepository telemetryRepository,
                           DispatchRecordRepository dispatchRecordRepository,
                           CommandService commandService) {
        this.deviceRepository = deviceRepository;
        this.telemetryRepository = telemetryRepository;
        this.dispatchRecordRepository = dispatchRecordRepository;
        this.commandService = commandService;
    }

    /** 调度结果 */
    public record DispatchOutcome(DispatchRecordEntity record, UavDeviceEntity device,
                                  CommandService.CommandView command) {
    }

    /**
     * 为任务执行一次 F09-Basic 调度并下发 GOTO。mission.status 应尚处于可指派状态
     * （由调用方 MissionService 校验）；无候选机 → 40002。
     */
    @Transactional
    public DispatchOutcome dispatch(MissionEntity mission, MissionWaypointEntity targetWaypoint) {
        if (mission.getTarget() == null || targetWaypoint == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mission target is missing");
        }
        double targetLat = targetWaypoint.getLatitude();
        double targetLon = targetWaypoint.getLongitude();

        List<UavDeviceEntity> candidates = deviceRepository
                .findByDeviceStatusInAndDeletedAtIsNull(DISPATCHABLE_STATUSES);

        // 电量过滤：最新遥测电量 > 25；无遥测回退设备快照；两者皆无 → 排除
        List<Candidate> scored = candidates.stream()
                .map(d -> toCandidate(d, targetLat, targetLon))
                .filter(c -> c.batteryPercent() != null
                        && c.batteryPercent().doubleValue() > MIN_BATTERY_PERCENT)
                .toList();
        if (scored.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                    "no available uav (status in " + DISPATCHABLE_STATUSES
                            + " and battery > " + (int) MIN_BATTERY_PERCENT + ")");
        }

        // 选机：候选含 UAV-001 直接选它；否则最高分
        Candidate selected = scored.stream()
                .filter(c -> PREFERRED_DEVICE_CODE.equals(c.device().getDeviceCode()))
                .findFirst()
                .orElseGet(() -> scored.stream()
                        .reduce((a, b) -> b.score() >= a.score() ? b : a)
                        .orElseThrow());
        String selectedReason = PREFERRED_DEVICE_CODE.equals(selected.device().getDeviceCode())
                ? "preferred demo device " + PREFERRED_DEVICE_CODE
                : "highest weighted score among candidates";

        Double distanceM = selected.distanceMeters();
        Instant now = Instant.now();

        DispatchRecordEntity record = new DispatchRecordEntity();
        record.setId(UUID.randomUUID());                                // id UUID PK（应用生成）
        record.setMissionId(mission.getId());                           // mission_id NOT NULL
        record.setIncidentId(mission.getIncidentId());                  // incident_id（透传）
        record.setSelectedUavId(selected.device().getId());             // selected_uav_id
        record.setAlgorithmType("F09-BASIC");                           // algorithm_type
        record.setScore(selected.score());                              // score 0~100
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("weights", Map.of(
                "distance", W_DISTANCE, "battery", W_BATTERY,
                "capability", W_CAPABILITY, "communication", W_COMMUNICATION));
        Map<String, Object> distanceFactorMap = new LinkedHashMap<>();
        distanceFactorMap.put("rawMeters", distanceM == null ? null : Math.round(distanceM));
        distanceFactorMap.put("normalized", selected.distanceFactor());
        factors.put("distance", distanceFactorMap);
        factors.put("battery", Map.of(
                "batteryPercent", selected.batteryPercent(),
                "normalized", selected.batteryFactor()));
        factors.put("capability", Map.of(
                "normalized", selected.capabilityFactor(),
                "note", "MVP 单机（RGB/热成像/云台齐备），未接 uav_capability 表，恒 1.0"));
        factors.put("communication", Map.of(
                "communicationStatus", selected.communicationStatus(),
                "normalized", selected.communicationFactor()));
        factors.put("candidateCount", scored.size());
        factors.put("selectedReason", selectedReason);
        record.setFactors(factors);                                     // factors JSONB
        record.setEstimatedDistance(distanceM == null
                ? null : Math.round(distanceM * 10) / 10.0);            // estimated_distance
        record.setEstimatedArrivalSeconds(distanceM == null
                ? null : (int) Math.round(distanceM / CRUISE_SPEED_MPS)); // estimated_arrival_seconds
        record.setDecisionTime(now);                                    // decision_time NOT NULL
        record.setResult("ASSIGNED");                                   // result
        record.setCreatedAt(now);                                       // created_at NOT NULL
        record = dispatchRecordRepository.save(record);

        // 自动下发 GOTO（经既有 CommandService → gateway；altitude/altitudeMode 成对出现，禁止裸高度）
        Double altitude = targetWaypoint.getAltitude() != null ? targetWaypoint.getAltitude() : 100.0;
        String altitudeMode = actionText(targetWaypoint, "altitudeMode") != null
                ? actionText(targetWaypoint, "altitudeMode") : "RELATIVE_TO_TAKEOFF";
        Map<String, Object> gotoParams = new LinkedHashMap<>();
        gotoParams.put("latitude", targetWaypoint.getLatitude());
        gotoParams.put("longitude", targetWaypoint.getLongitude());
        gotoParams.put("altitude", altitude);
        gotoParams.put("altitudeMode", altitudeMode);
        gotoParams.put("speed", CRUISE_SPEED_MPS);
        CommandService.CommandView command = commandService.create(
                selected.device().getDeviceCode(),
                new CommandService.CommandCreateRequest("GOTO", gotoParams));

        log.info("dispatch mission {} -> {} (score={}, dispatchId={}, commandId={})",
                mission.getMissionNo(), selected.device().getDeviceCode(),
                record.getScore(), record.getId(), command.id());
        return new DispatchOutcome(record, selected.device(), command);
    }

    // ---------------- 评分 ----------------

    /** 单机评分因子（score = 100×Σ weight×factor） */
    private record Candidate(UavDeviceEntity device,
                             BigDecimal batteryPercent,
                             Double distanceMeters,
                             double distanceFactor,
                             double batteryFactor,
                             double capabilityFactor,
                             double communicationFactor,
                             String communicationStatus,
                             double score) {
    }

    private Candidate toCandidate(UavDeviceEntity device, double targetLat, double targetLon) {
        Optional<UavTelemetryEntity> latestTelemetry =
                telemetryRepository.findLatestByUavId(device.getId());

        // 电量：最新遥测优先，回退设备快照（皆无 → 不可调度，factor 中性值，由上层过滤剔除）
        BigDecimal battery = latestTelemetry
                .map(UavTelemetryEntity::getBatteryPercent)
                .orElseGet(device::getBatteryPercent);
        double batteryFactor = battery == null ? 0.0 : battery.doubleValue() / 100.0;

        // 距离：当前位置优先，回退 home；都无 → 未知（中性 0.5）
        Double lat = device.getCurrentLatitude() != null
                ? device.getCurrentLatitude() : device.getHomeLatitude();
        Double lon = device.getCurrentLongitude() != null
                ? device.getCurrentLongitude() : device.getHomeLongitude();
        Double distanceM = null;
        double distanceFactor = 0.5;
        if (lat != null && lon != null) {
            distanceM = GeoUtils.haversineMeters(lat, lon, targetLat, targetLon);
            distanceFactor = Math.max(0.0, 1.0 - distanceM / REF_DISTANCE_M);
        }

        // 能力：MVP 单机具备 RGB/热成像/云台，恒 1.0（uav_capability 未接入）
        double capabilityFactor = 1.0;

        // 通信：最新遥测链路状态；无遥测按 0.8
        String commStatus = latestTelemetry
                .map(UavTelemetryEntity::getCommunicationStatus)
                .orElse(null);
        double communicationFactor = commStatus == null ? 0.8
                : ("CONNECTED".equals(commStatus) ? 1.0 : 0.5);

        double score = 100.0 * (W_DISTANCE * distanceFactor
                + W_BATTERY * batteryFactor
                + W_CAPABILITY * capabilityFactor
                + W_COMMUNICATION * communicationFactor);
        score = BigDecimal.valueOf(score).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new Candidate(device, battery, distanceM, distanceFactor,
                batteryFactor, capabilityFactor, communicationFactor, commStatus, score);
    }

    private static String actionText(MissionWaypointEntity waypoint, String key) {
        Object v = waypoint.getAction() == null ? null : waypoint.getAction().get(key);
        return v == null ? null : String.valueOf(v);
    }
}

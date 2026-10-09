package com.forestfire.uav.risk;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.fire.FireIncidentEntity;
import com.forestfire.uav.fire.FireIncidentRepository;
import com.forestfire.uav.fire.FirePolygonEntity;
import com.forestfire.uav.fire.FirePolygonRepository;
import com.forestfire.uav.fire.GeoUtils;
import com.forestfire.uav.media.AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 火势趋势预测服务（F11 一期 Level 1 规则，03号第91.5节）：
 * <ol>
 *   <li>触发：F04 核验轮次落库钩子（{@link #autoRunAfterVerification}，REQUIRES_NEW
 *       隔离，失败不影响核验）+ 页面手动重跑（POST /predictions/run）；
 *       用户决策 2026-10-09：自动触发 + 可手动重跑；</li>
 *   <li>火心：最新 fire_polygon 质心，无多边形回退事件最新火点坐标；
 *       基准半径 r=√(A/π)，无多边形 150m；</li>
 *   <li>模型参数/气象：唯一来源 application.yml risk.prediction.*（镜像 constants v1.6）；
 *       气象为 ai-service mock（与 DJI 遥测分离，03号第19.3节硬约束）；</li>
 *   <li>落库：fire_prediction 每档一行（30/60min），confidence=0.78，环境快照存
 *       environmental_input，过程数据存 prediction_result。</li>
 * </ol>
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    /** 活跃火情状态（F11 预测目标） */
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "SUSPECTED", "VERIFYING", "CONFIRMED", "TRACKING");

    /** fire-predict-level1 固定 UUID（model_id 列） */
    private static final UUID MODEL_ID = UUID.nameUUIDFromBytes("fire-predict-level1".getBytes());

    /** 无火场多边形时的默认基准半径（米，= F05 首轮分割半径） */
    private static final double DEFAULT_BASE_RADIUS_M = 150.0;

    @Value("${risk.prediction.horizons-min:30,60}")
    private String horizonsConfig;
    @Value("${risk.prediction.base-spread-rate-m-per-min:1.5}")
    private double baseSpreadRate;
    @Value("${risk.prediction.wind-speed-factor-per-mps:0.20}")
    private double windSpeedFactor;
    @Value("${risk.prediction.length-width-ratio-base:1.5}")
    private double ratioBase;
    @Value("${risk.prediction.length-width-ratio-per-mps:0.25}")
    private double ratioPerMps;
    @Value("${risk.prediction.length-width-ratio-max:3.0}")
    private double ratioMax;
    @Value("${risk.prediction.confidence:0.78}")
    private double confidence;

    private final FireIncidentRepository incidentRepository;
    private final FirePolygonRepository polygonRepository;
    private final FirePredictionRepository predictionRepository;
    private final AiServiceClient aiServiceClient;

    public PredictionService(FireIncidentRepository incidentRepository,
                             FirePolygonRepository polygonRepository,
                             FirePredictionRepository predictionRepository,
                             AiServiceClient aiServiceClient) {
        this.incidentRepository = incidentRepository;
        this.polygonRepository = polygonRepository;
        this.predictionRepository = predictionRepository;
        this.aiServiceClient = aiServiceClient;
    }

    // ---------------- 触发入口 ----------------

    /**
     * F04 核验轮次落库钩子（03号第91.1节用户决策）：REQUIRES_NEW 独立事务，
     * 失败仅告警不影响核验主流程；FALSE_ALARM 事件不预测。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRunAfterVerification(FireIncidentEntity incident) {
        if ("FALSE_ALARM".equals(incident.getStatus())) {
            return;
        }
        try {
            List<RiskViews.PredictionView> views = run(incident.getId());
            log.info("F11 auto prediction after verification: incident={} rows={}",
                    incident.getIncidentNo(), views.size());
        } catch (Exception e) {
            log.warn("F11 auto prediction failed for incident {}: {}",
                    incident.getIncidentNo(), e.getMessage());
        }
    }

    /** 手动重跑（POST /predictions/run）：incidentId 缺省取最新活跃火情 */
    @Transactional
    public List<RiskViews.PredictionView> run(UUID incidentId) {
        FireIncidentEntity incident = resolveIncident(incidentId);

        // 火心与基准半径：最新 fire_polygon 质心优先，回退事件最新火点
        List<FirePolygonEntity> polygons =
                polygonRepository.findByIncidentIdOrderByCreatedAtAsc(incident.getId());
        double lat;
        double lon;
        double baseRadiusM;
        if (!polygons.isEmpty()) {
            var centroid = polygons.get(polygons.size() - 1).getPolygon().getCentroid();
            lat = centroid.getY();
            lon = centroid.getX();
            Double area = polygons.get(polygons.size() - 1).getAreaSquareMeter();
            baseRadiusM = area != null && area > 0 ? GeoUtils.radiusFromArea(area) : DEFAULT_BASE_RADIUS_M;
        } else if (incident.getLatitude() != null && incident.getLongitude() != null) {
            lat = incident.getLatitude();
            lon = incident.getLongitude();
            baseRadiusM = DEFAULT_BASE_RADIUS_M;
        } else {
            throw new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                    "no fire polygon or fire point for incident: " + incident.getIncidentNo());
        }

        Map<String, Object> weather = aiServiceClient.weatherCurrent();
        List<Integer> horizons = horizons();
        List<AiServiceClient.FirePredictionItem> items = aiServiceClient.predict(
                lat, lon, baseRadiusM, horizons, weather, modelParams());

        Instant now = Instant.now();
        List<RiskViews.PredictionView> views = new ArrayList<>();
        for (AiServiceClient.FirePredictionItem item : items) {
            if (item.forecastMinutes() == null || item.ring() == null || item.ring().size() < 3) {
                continue;
            }
            FirePredictionEntity entity = new FirePredictionEntity();
            entity.setId(UUID.randomUUID());
            entity.setIncidentId(incident.getId());
            entity.setModelId(MODEL_ID);
            entity.setBaseTime(now);
            entity.setForecastMinutes(item.forecastMinutes());
            entity.setPredictedGeometry(GeoUtils.toMultiPolygon(item.ring()));
            entity.setPredictedAreaSquareMeter(item.areaSquareMeters());
            entity.setConfidence(BigDecimal.valueOf(confidence));
            entity.setEnvironmentalInput(weather);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("baseRadiusM", Math.round(baseRadiusM * 10.0) / 10.0);
            result.put("semiMajorM", item.semiMajorM());
            result.put("semiMinorM", item.semiMinorM());
            result.put("fireHeadDirectionDeg", item.fireHeadDirectionDeg());
            result.put("spreadRateMPerMin", item.spreadRateMPerMin());
            result.put("lengthWidthRatio", item.lengthWidthRatio());
            result.put("model", "LEVEL1_ELLIPSE");
            entity.setPredictionResult(result);
            entity.setCreatedAt(now);
            predictionRepository.save(entity);
            views.add(toView(entity));
        }
        log.info("F11 prediction: incident={} horizons={} center=({},{}) baseRadiusM={}",
                incident.getIncidentNo(), horizons,
                String.format("%.6f", lat), String.format("%.6f", lon),
                Math.round(baseRadiusM));
        return views;
    }

    /** 最新预测（GET /predictions/latest）：incidentId 缺省取最新活跃火情；无活跃火情返回空数组 */
    @Transactional(readOnly = true)
    public List<RiskViews.PredictionView> latest(UUID incidentId) {
        FireIncidentEntity incident;
        try {
            incident = resolveIncident(incidentId);
        } catch (BusinessException e) {
            return List.of(); // 无活跃火情 → 空数组（openapi 约定，前端不视为错误）
        }
        List<FirePredictionEntity> rows =
                predictionRepository.findByIncidentIdOrderByCreatedAtDesc(incident.getId());
        if (rows.isEmpty()) {
            return List.of();
        }
        Instant latestBaseTime = rows.get(0).getBaseTime();
        return rows.stream()
                .filter(r -> latestBaseTime.equals(r.getBaseTime()))
                .map(PredictionService::toView)
                .toList();
    }

    // ---------------- 内部工具 ----------------

    /** incidentId 空 → 最新活跃火情；否则校验存在（UUID/id 宽容解析由 controller 完成） */
    private FireIncidentEntity resolveIncident(UUID incidentId) {
        if (incidentId == null) {
            return incidentRepository.findByStatusInOrderByUpdatedAtDesc(ACTIVE_STATUSES).stream()
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                            "no active fire incident (status in " + ACTIVE_STATUSES + ")"));
        }
        FireIncidentEntity incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                        "incident not found: " + incidentId));
        if (!ACTIVE_STATUSES.contains(incident.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "prediction only allowed for active incidents, current=" + incident.getStatus());
        }
        return incident;
    }

    private List<Integer> horizons() {
        List<Integer> horizons = new ArrayList<>();
        for (String part : horizonsConfig.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                horizons.add(Integer.parseInt(trimmed));
            }
        }
        if (horizons.isEmpty()) {
            horizons.add(30);
            horizons.add(60);
        }
        return horizons;
    }

    private Map<String, Object> modelParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("baseSpreadRateMPerMin", baseSpreadRate);
        params.put("windSpeedFactorPerMps", windSpeedFactor);
        params.put("lengthWidthRatioBase", ratioBase);
        params.put("lengthWidthRatioPerMps", ratioPerMps);
        params.put("lengthWidthRatioMax", ratioMax);
        params.put("confidence", confidence);
        return params;
    }

    private static RiskViews.PredictionView toView(FirePredictionEntity entity) {
        return new RiskViews.PredictionView(entity.getId(), entity.getIncidentId(),
                entity.getBaseTime(), entity.getForecastMinutes(),
                GeoUtils.multiPolygonToGeoJson(entity.getPredictedGeometry()),
                entity.getPredictedAreaSquareMeter(), entity.getConfidence(),
                entity.getEnvironmentalInput(), entity.getPredictionResult(), entity.getCreatedAt());
    }
}

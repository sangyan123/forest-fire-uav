package com.forestfire.uav.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Service 客户端（07号第21~23章，mock provider）：
 * <ul>
 *   <li>POST /ai/v1/detection      — F01 火情检测（detections[]）</li>
 *   <li>POST /ai/v1/localization   — F03 火点定位（lat/lon/accuracy/method）</li>
 *   <li>POST /ai/v1/verification   — F04 二次核验（decision/confidence/evidence）</li>
 * </ul>
 * 响应统一 {code, message, data}；code!=0 或网络异常抛出，由调用方决定降级策略。
 */
@Service
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestClient aiServiceRestClient;

    public AiServiceClient(RestClient aiServiceRestClient) {
        this.aiServiceRestClient = aiServiceRestClient;
    }

    /** F01 单个检测目标 */
    public record DetectionItem(String className, double confidence, List<Double> bbox) {
    }

    /** F03 定位结果（07号第22章 data） */
    public record LocalizationResult(Double latitude, Double longitude, Double accuracy, String method) {
    }

    /** F04 核验结果（07号第23章 data） */
    public record VerificationResult(String decision, Double confidence, Map<String, Double> evidence) {
    }

    /** F05 分割结果：ring 为 [lat,lon] 闭合环（07号第24章扩展：growthStep 轮次半径 150m+90m/轮） */
    public record SegmentationResult(List<double[]> ring, Double radiusMeters,
                                     Double areaSquareMeters, Integer growthStep) {
    }

    /** F06 跟踪结果（07号第25章 data） */
    public record TrackingResult(Double direction, Double speed, Double areaGrowthRate, String trend) {
    }

    /** F07 五因子分值（03号第91.5节，cellCode → 五因子 0~100） */
    public record RiskFactorItem(String cellCode, Double historical, Double weather,
                                 Double vegetation, Double terrain, Double humanActivity) {
    }

    /** F11 单档预测（03号第91.5节，ring 为 [lat,lon] 闭合环） */
    public record FirePredictionItem(Integer forecastMinutes, List<double[]> ring,
                                     Double areaSquareMeters, Double semiMajorM, Double semiMinorM,
                                     Double fireHeadDirectionDeg, Double spreadRateMPerMin,
                                     Double lengthWidthRatio, Double confidence) {
    }

    /** F08 蛇形航点（03号第91.5节） */
    public record PatrolWaypoint(int sequenceNo, double latitude, double longitude, double altitude) {
    }

    public record PatrolWaypointsResult(List<PatrolWaypoint> waypoints, Double routeLengthM,
                                        Double estimatedDurationMin) {
    }

    /** F10 单条过火分带（03号第18章，ring 为 [lat,lon] 闭合环） */
    public record AssessmentBand(String areaType, List<double[]> ring,
                                 Double areaSquareMeter, Double confidence) {
    }

    /** F10 灾后过火区域评估结果（三分带 + 四项统计） */
    public record AssessmentResult(List<AssessmentBand> bands,
                                   Double burnedAreaSquareMeter,
                                   Double affectedForestAreaSquareMeter,
                                   Double affectedRoadLengthMeter,
                                   Double affectedFacilityAreaSquareMeter,
                                   Double confidence) {
    }

    /** F01 火情检测：入参 {taskId, mediaId}，出 data.detections[] */
    public List<DetectionItem> detect(UUID taskId, UUID mediaId) {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId.toString());
        body.put("mediaId", mediaId.toString());
        JsonNode data = postAndUnwrap("/ai/v1/detection", body);
        List<DetectionItem> items = new ArrayList<>();
        for (JsonNode d : data.path("detections")) {
            List<Double> bbox = new ArrayList<>();
            for (JsonNode b : d.path("bbox")) {
                bbox.add(b.asDouble());
            }
            items.add(new DetectionItem(d.path("class").asText(null), d.path("confidence").asDouble(0), bbox));
        }
        return items;
    }

    /** F03 火点定位：入参 {taskId, media:{position:{latitude,longitude}}}，出 data{latitude,longitude,accuracy,method} */
    public LocalizationResult localize(UUID taskId, double latitude, double longitude) {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId.toString());
        body.put("media", Map.of("position", Map.of("latitude", latitude, "longitude", longitude)));
        JsonNode data = postAndUnwrap("/ai/v1/localization", body);
        return new LocalizationResult(
                doubleOrNull(data, "latitude"),
                doubleOrNull(data, "longitude"),
                doubleOrNull(data, "accuracy"),
                textOrNull(data, "method"));
    }

    /** F04 二次核验：入参 {taskId, incidentId, evidence:{rgb,thermal,temporal,spatial}}，出 data{decision,confidence,evidence} */
    public VerificationResult verify(UUID taskId, String incidentId, Map<String, Object> evidence) {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId.toString());
        body.put("incidentId", incidentId);
        body.put("evidence", evidence);
        JsonNode data = postAndUnwrap("/ai/v1/verification", body);
        Map<String, Double> ev = new LinkedHashMap<>();
        data.path("evidence").fields().forEachRemaining(e -> {
            if (e.getValue().isNumber()) {
                ev.put(e.getKey(), e.getValue().asDouble());
            }
        });
        return new VerificationResult(textOrNull(data, "decision"), doubleOrNull(data, "confidence"), ev);
    }

    /** F05 火场分割：入参 {taskId, incidentId, center:{latitude,longitude}, growthStep}，
     * 出 data{polygon:[[lat,lon]...], radiusMeters, areaSquareMeters, growthStep} */
    public SegmentationResult segment(UUID taskId, String incidentId,
                                      double latitude, double longitude, int growthStep) {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId.toString());
        body.put("incidentId", incidentId);
        body.put("center", Map.of("latitude", latitude, "longitude", longitude));
        body.put("growthStep", growthStep);
        JsonNode data = postAndUnwrap("/ai/v1/segmentation", body);
        List<double[]> ring = new ArrayList<>();
        for (JsonNode p : data.path("polygon")) {
            if (p.isArray() && p.size() >= 2 && p.get(0).isNumber() && p.get(1).isNumber()) {
                ring.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()});
            }
        }
        Integer step = data.path("growthStep").isInt() ? data.path("growthStep").asInt() : null;
        return new SegmentationResult(ring,
                doubleOrNull(data, "radiusMeters"),
                doubleOrNull(data, "areaSquareMeters"),
                step);
    }

    /** F06 火势跟踪：入参 {taskId, incidentId}，出 data{direction, speed, areaGrowthRate, trend} */
    public TrackingResult track(UUID taskId, String incidentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId.toString());
        body.put("incidentId", incidentId);
        JsonNode data = postAndUnwrap("/ai/v1/tracking", body);
        return new TrackingResult(
                doubleOrNull(data, "direction"),
                doubleOrNull(data, "speed"),
                doubleOrNull(data, "areaGrowthRate"),
                textOrNull(data, "trend"));
    }

    /** 气象 mock 当前值（与 DJI 遥测分离，03号第19.3节硬约束） */
    public Map<String, Object> weatherCurrent() {
        JsonNode data = aiServiceRestClient.get()
                .uri("/ai/v1/weather/current")
                .retrieve()
                .body(JsonNode.class);
        if (data == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI service empty response: weather");
        }
        JsonNode d = data.path("data");
        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("source", textOrNull(d, "source"));
        weather.put("tempC", doubleOrNull(d, "tempC"));
        weather.put("humidityPct", doubleOrNull(d, "humidityPct"));
        weather.put("windSpeedMps", doubleOrNull(d, "windSpeedMps"));
        weather.put("windDirectionDeg", doubleOrNull(d, "windDirectionDeg"));
        return weather;
    }

    /** F07 五因子分值：入参 {cells:[{cellCode}], weather}，出 data.factors[]（03号第91.5节） */
    public List<RiskFactorItem> riskFactors(List<String> cellCodes, Map<String, Object> weather) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (String code : cellCodes) {
            cells.add(Map.of("cellCode", code));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("cells", cells);
        body.put("weather", weather);
        JsonNode data = postAndUnwrap("/ai/v1/risk/factors", body);
        List<RiskFactorItem> items = new ArrayList<>();
        for (JsonNode n : data.path("factors")) {
            items.add(new RiskFactorItem(
                    textOrNull(n, "cellCode"),
                    doubleOrNull(n, "HISTORICAL"),
                    doubleOrNull(n, "WEATHER"),
                    doubleOrNull(n, "VEGETATION"),
                    doubleOrNull(n, "TERRAIN"),
                    doubleOrNull(n, "HUMAN_ACTIVITY")));
        }
        return items;
    }

    /** F11 椭圆扩散预测：params 唯一来源 backend application.yml risk.prediction.*（透传） */
    public List<FirePredictionItem> predict(double latitude, double longitude, double baseRadiusM,
                                            List<Integer> horizons, Map<String, Object> weather,
                                            Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("center", Map.of("latitude", latitude, "longitude", longitude));
        body.put("baseRadiusM", baseRadiusM);
        body.put("horizons", horizons);
        body.put("weather", weather);
        body.put("params", params);
        JsonNode data = postAndUnwrap("/ai/v1/fire/predict", body);
        List<FirePredictionItem> items = new ArrayList<>();
        for (JsonNode n : data.path("predictions")) {
            List<double[]> ring = new ArrayList<>();
            for (JsonNode p : n.path("ring")) {
                if (p.isArray() && p.size() >= 2 && p.get(0).isNumber() && p.get(1).isNumber()) {
                    ring.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()});
                }
            }
            items.add(new FirePredictionItem(
                    n.path("forecastMinutes").isInt() ? n.path("forecastMinutes").asInt() : null,
                    ring,
                    doubleOrNull(n, "areaSquareMeters"),
                    doubleOrNull(n, "semiMajorM"),
                    doubleOrNull(n, "semiMinorM"),
                    doubleOrNull(n, "fireHeadDirectionDeg"),
                    doubleOrNull(n, "spreadRateMPerMin"),
                    doubleOrNull(n, "lengthWidthRatio"),
                    doubleOrNull(n, "confidence")));
        }
        return items;
    }

    /** F08 蛇形覆盖航点：laneSpacing/altitude/speed 唯一来源 backend application.yml risk.patrol.* */
    public PatrolWaypointsResult patrolWaypoints(double minLat, double minLon,
                                                 double maxLat, double maxLon,
                                                 double laneSpacingM, double altitudeM, double speedMps) {
        Map<String, Object> body = new HashMap<>();
        body.put("bounds", Map.of("minLat", minLat, "minLon", minLon, "maxLat", maxLat, "maxLon", maxLon));
        body.put("laneSpacingM", laneSpacingM);
        body.put("altitudeM", altitudeM);
        body.put("speedMps", speedMps);
        JsonNode data = postAndUnwrap("/ai/v1/patrol/waypoints", body);
        List<PatrolWaypoint> waypoints = new ArrayList<>();
        for (JsonNode n : data.path("waypoints")) {
            waypoints.add(new PatrolWaypoint(
                    n.path("sequenceNo").asInt(0),
                    n.path("latitude").asDouble(),
                    n.path("longitude").asDouble(),
                    n.path("altitude").asDouble()));
        }
        return new PatrolWaypointsResult(waypoints,
                doubleOrNull(data, "routeLengthM"), doubleOrNull(data, "estimatedDurationMin"));
    }

    /** F10 灾后过火区域评估：入参 {incidentId, lastPolygonRing:[[lat,lon]...], params{模型参数透传}}，
     * 出 data{rings{SEVERE/MODERATE/LIGHT:[[lat,lon]...]}, burnedAreaSquareMeter,
     * affectedForestAreaSquareMeter, affectedRoadLengthMeter, affectedFacilityAreaSquareMeter, confidence}。
     * params 唯一来源 backend application.yml assessment.*（镜像 constants v1.7） */
    public AssessmentResult assessmentBurnedArea(UUID incidentId, List<double[]> lastPolygonRing,
                                                Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("incidentId", incidentId.toString());
        body.put("lastPolygonRing", lastPolygonRing);
        body.put("params", params);
        JsonNode data = postAndUnwrap("/ai/v1/assessment/burned-area", body);
        JsonNode rings = data.path("rings");
        List<AssessmentBand> bands = new ArrayList<>();
        for (String type : new String[]{"SEVERE", "MODERATE", "LIGHT"}) {
            List<double[]> ring = new ArrayList<>();
            for (JsonNode p : rings.path(type)) {
                if (p.isArray() && p.size() >= 2 && p.get(0).isNumber() && p.get(1).isNumber()) {
                    ring.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()});
                }
            }
            bands.add(new AssessmentBand(type, ring, null, null));
        }
        return new AssessmentResult(bands,
                doubleOrNull(data, "burnedAreaSquareMeter"),
                doubleOrNull(data, "affectedForestAreaSquareMeter"),
                doubleOrNull(data, "affectedRoadLengthMeter"),
                doubleOrNull(data, "affectedFacilityAreaSquareMeter"),
                doubleOrNull(data, "confidence"));
    }

    // ---------------- 内部工具 ----------------

    /** POST 并解包 {code,message,data}：code!=0 → 50000（约定外补充码） */
    private JsonNode postAndUnwrap(String uri, Object body) {
        JsonNode root = aiServiceRestClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (root == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI service empty response: " + uri);
        }
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "AI service error(" + code + "): " + root.path("message").asText(""));
        }
        return root.path("data");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }
}

package com.forestfire.uav.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 火情风险检测页视图集合（03号第91.4节 API 契约，字段名与 openapi.yaml
 * RiskArea/RiskFactor/RiskAssessSummary/FirePrediction/PatrolSuggestion 一致）。
 */
public final class RiskViews {

    private RiskViews() {
    }

    /** 风险区域（GET /risk/areas 列表项与地图图层要素） */
    public record AreaView(UUID areaId, String areaCode, String areaName,
                           List<List<Double>> geometry, Double riskScore, String riskLevel,
                           Instant evaluationTime) {
    }

    /** 区域详情 = AreaView 字段 + factors 五因子分项（openapi allOf） */
    public record AreaDetailView(UUID areaId, String areaCode, String areaName,
                                 List<List<Double>> geometry, Double riskScore, String riskLevel,
                                 Instant evaluationTime, List<FactorView> factors) {
    }

    /** 五因子分项（risk_feature 行） */
    public record FactorView(String featureType, Double featureValue, Double weight,
                             Double contribution, String source) {
    }

    /** 评估摘要（POST /risk/assess 响应） */
    public record AssessSummary(int assessedCount, int highCount, int mediumCount,
                                int lowCount, Instant assessedAt) {
    }

    /** 火势预测（fire_prediction 行） */
    public record PredictionView(UUID predictionId, UUID incidentId, Instant baseTime,
                                 Integer forecastMinutes, List<List<Double>> predictedGeometry,
                                 Double predictedAreaSquareMeter, BigDecimal confidence,
                                 Map<String, Object> environmentalInput,
                                 Map<String, Object> predictionResult, Instant createdAt) {
    }

    /** 巡检航点 */
    public record WaypointView(Integer sequenceNo, Double latitude, Double longitude, Double altitude) {
    }

    /** 巡检建议（patrol_area 行 + 关联区域摘要；waypoints 存 reason JSONB） */
    public record SuggestionView(UUID suggestionId, UUID riskAreaId, String areaCode,
                                 Double riskScore, List<List<Double>> geometry,
                                 List<WaypointView> waypoints, Integer priority,
                                 Double estimatedDurationMin, String status, UUID missionId,
                                 Instant generatedAt) {
    }
}

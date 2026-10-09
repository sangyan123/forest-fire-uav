package com.forestfire.uav.risk;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.fire.GeoUtils;
import com.forestfire.uav.media.AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 风险评估服务（F07，03号第91.5节一期算法）：
 * <ol>
 *   <li>网格自动划分：原点 (30.12,114.12)、1km×1km、5×5=25 格（constants.yaml#risk_assessment#grid），
 *       area_code="GRID-R{row}-C{col}" 幂等 upsert；</li>
 *   <li>五因子加权：ai-service mock 按格确定性出分（historical/weather/vegetation/terrain/
 *       human_activity），权重与阈值唯一来源 application.yml risk.*（镜像 constants v1.6）；
 *       ≥70 HIGH / ≥40 MEDIUM / 其余 LOW；</li>
 *   <li>落库：risk_area 更新 + risk_feature 先删后插 5 行 + risk_area_assessment 新版本；
 *       既有 SUGGESTED 巡检建议置 EXPIRED（03号第91.3节数据流）；</li>
 *   <li>启动初始化：risk_area 为空时自动评估一次（constants#assess_trigger）。</li>
 * </ol>
 */
@Service
public class RiskService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    /** risk-model-v1 固定 UUID（model_id 列） */
    private static final UUID MODEL_ID = UUID.nameUUIDFromBytes("risk-model-v1".getBytes());
    private static final String MODEL_VERSION = "risk-model-v1";
    private static final String ALGO_CONFIG_VERSION = "risk-assessment-config-v1.0";
    private static final String DATASET_VERSION = "mock-grid-5x5";
    private static final String FACTOR_SOURCE = "ai-service mock provider";

    /** 五因子类型（feature_type 列，与 ai-service 返回键一致） */
    private static final String[] FACTOR_TYPES = {
            "HISTORICAL", "WEATHER", "VEGETATION", "TERRAIN", "HUMAN_ACTIVITY"};

    // ---- 网格参数（constants.yaml#risk_assessment#grid） ----
    @Value("${risk.grid.cell-size-m:1000}")
    private double cellSizeM;
    @Value("${risk.grid.origin-lat:30.12}")
    private double originLat;
    @Value("${risk.grid.origin-lon:114.12}")
    private double originLon;
    @Value("${risk.grid.grid-cols:5}")
    private int gridCols;
    @Value("${risk.grid.grid-rows:5}")
    private int gridRows;

    // ---- 五因子权重（constants.yaml#risk_assessment#weights，和=1.0） ----
    @Value("${risk.weights.historical:0.30}")
    private double wHistorical;
    @Value("${risk.weights.weather:0.25}")
    private double wWeather;
    @Value("${risk.weights.vegetation:0.20}")
    private double wVegetation;
    @Value("${risk.weights.terrain:0.15}")
    private double wTerrain;
    @Value("${risk.weights.human-activity:0.10}")
    private double wHumanActivity;

    // ---- 等级阈值（constants.yaml#risk_assessment#level_thresholds） ----
    @Value("${risk.level-thresholds.high-min:70}")
    private double highMin;
    @Value("${risk.level-thresholds.medium-min:40}")
    private double mediumMin;

    private final RiskAreaRepository areaRepository;
    private final RiskAreaAssessmentRepository assessmentRepository;
    private final RiskFeatureRepository featureRepository;
    private final PatrolAreaRepository patrolAreaRepository;
    private final AiServiceClient aiServiceClient;

    public RiskService(RiskAreaRepository areaRepository,
                       RiskAreaAssessmentRepository assessmentRepository,
                       RiskFeatureRepository featureRepository,
                       PatrolAreaRepository patrolAreaRepository,
                       AiServiceClient aiServiceClient) {
        this.areaRepository = areaRepository;
        this.assessmentRepository = assessmentRepository;
        this.featureRepository = featureRepository;
        this.patrolAreaRepository = patrolAreaRepository;
        this.aiServiceClient = aiServiceClient;
    }

    // ---------------- 评估 ----------------

    @Transactional
    public RiskViews.AssessSummary assess() {
        Instant now = Instant.now();
        Map<String, Object> weather = aiServiceClient.weatherCurrent();

        List<String> cellCodes = new ArrayList<>();
        for (int row = 1; row <= gridRows; row++) {
            for (int col = 1; col <= gridCols; col++) {
                cellCodes.add(cellCode(row, col));
            }
        }
        List<AiServiceClient.RiskFactorItem> factorItems = aiServiceClient.riskFactors(cellCodes, weather);
        if (factorItems.size() != cellCodes.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "AI risk factors size mismatch: expect " + cellCodes.size()
                            + " got " + factorItems.size());
        }

        int high = 0;
        int medium = 0;
        int low = 0;
        for (AiServiceClient.RiskFactorItem item : factorItems) {
            Map<String, Double> values = Map.of(
                    "HISTORICAL", item.historical() == null ? 0.0 : item.historical(),
                    "WEATHER", item.weather() == null ? 0.0 : item.weather(),
                    "VEGETATION", item.vegetation() == null ? 0.0 : item.vegetation(),
                    "TERRAIN", item.terrain() == null ? 0.0 : item.terrain(),
                    "HUMAN_ACTIVITY", item.humanActivity() == null ? 0.0 : item.humanActivity());
            Map<String, Double> weights = Map.of(
                    "HISTORICAL", wHistorical, "WEATHER", wWeather, "VEGETATION", wVegetation,
                    "TERRAIN", wTerrain, "HUMAN_ACTIVITY", wHumanActivity);
            double score = 0.0;
            for (String type : FACTOR_TYPES) {
                score += values.get(type) * weights.get(type);
            }
            score = Math.round(score * 10.0) / 10.0;
            String level = levelOf(score);
            switch (level) {
                case "HIGH" -> high++;
                case "MEDIUM" -> medium++;
                default -> low++;
            }

            int[] rc = rowColOf(item.cellCode());
            RiskAreaEntity area = areaRepository.findByAreaCode(item.cellCode()).orElseGet(() -> {
                RiskAreaEntity created = new RiskAreaEntity();
                created.setId(UUID.randomUUID());
                created.setAreaCode(item.cellCode());
                created.setAreaName("网格 " + item.cellCode().replace("GRID-", ""));
                created.setCreatedAt(now);
                return created;
            });
            area.setGeometry(cellGeometry(rc[0], rc[1]));
            area.setRiskScore(score);
            area.setRiskLevel(level);
            area.setModelId(MODEL_ID);
            area.setEvaluationTime(now);
            area.setUpdatedAt(now);
            area = areaRepository.save(area);

            // 因子分项：先删后插（risk_feature 每区域始终保留最近一轮 5 行）
            featureRepository.deleteByRiskAreaId(area.getId());
            for (String type : FACTOR_TYPES) {
                RiskFeatureEntity feature = new RiskFeatureEntity();
                feature.setId(UUID.randomUUID());
                feature.setRiskAreaId(area.getId());
                feature.setFeatureType(type);
                feature.setFeatureValue(values.get(type));
                feature.setWeight(weights.get(type));
                feature.setContribution(Math.round(values.get(type) * weights.get(type) * 10.0) / 10.0);
                feature.setSource(FACTOR_SOURCE);
                feature.setEvaluationTime(now);
                featureRepository.save(feature);
            }

            // 评估版本：UNIQUE(area_id, assessed_at) — 每次调用一个 now，天然满足
            RiskAreaAssessmentEntity assessment = new RiskAreaAssessmentEntity();
            assessment.setId(UUID.randomUUID());
            assessment.setAreaId(area.getId());
            assessment.setAssessedAt(now);
            assessment.setRiskScore(score);
            assessment.setRiskLevel(level);
            assessment.setModelVersion(MODEL_VERSION);
            assessment.setAlgorithmConfigVersion(ALGO_CONFIG_VERSION);
            assessment.setDatasetVersion(DATASET_VERSION);
            Map<String, Object> factorSnapshot = new LinkedHashMap<>();
            for (String type : FACTOR_TYPES) {
                factorSnapshot.put(type, values.get(type));
            }
            assessment.setFactors(factorSnapshot);
            assessment.setCreatedAt(now);
            assessmentRepository.save(assessment);
        }

        // 重评估后旧建议过期（03号第91.3节）
        List<PatrolAreaEntity> suggested = patrolAreaRepository.findByStatus("SUGGESTED");
        for (PatrolAreaEntity suggestion : suggested) {
            suggestion.setStatus("EXPIRED");
            patrolAreaRepository.save(suggestion);
        }

        log.info("risk assess done: {} cells (high={}, medium={}, low={}, expiredSuggestions={})",
                cellCodes.size(), high, medium, low, suggested.size());
        return new RiskViews.AssessSummary(cellCodes.size(), high, medium, low, now);
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<RiskViews.AreaView> list(String level, double[] bbox) {
        List<RiskAreaEntity> areas = (level == null || level.isBlank())
                ? areaRepository.findAllByOrderByRiskScoreDesc()
                : areaRepository.findByRiskLevelOrderByRiskScoreDesc(
                        level.trim().toUpperCase(Locale.ROOT));
        List<RiskViews.AreaView> views = new ArrayList<>();
        for (RiskAreaEntity area : areas) {
            if (bbox != null && !intersects(area, bbox)) {
                continue;
            }
            views.add(toAreaView(area));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public RiskViews.AreaDetailView detail(UUID areaId) {
        RiskAreaEntity area = areaRepository.findById(areaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "risk area not found: " + areaId));
        List<RiskViews.FactorView> factors = featureRepository
                .findByRiskAreaIdOrderByContributionDesc(area.getId()).stream()
                .map(f -> new RiskViews.FactorView(f.getFeatureType(), f.getFeatureValue(),
                        f.getWeight(), f.getContribution(), f.getSource()))
                .toList();
        return new RiskViews.AreaDetailView(area.getId(), area.getAreaCode(), area.getAreaName(),
                GeoUtils.multiPolygonToGeoJson(area.getGeometry()), area.getRiskScore(),
                area.getRiskLevel(), area.getEvaluationTime(), factors);
    }

    // ---------------- 启动初始化 ----------------

    /** 启动时 risk_area 为空则评估一次（constants#assess_trigger；ai-service 不可用不阻塞启动） */
    @Override
    public void run(ApplicationArguments args) {
        if (areaRepository.count() > 0) {
            return;
        }
        try {
            assess();
            log.info("risk assess initialized at startup");
        } catch (Exception e) {
            log.error("startup risk assess failed (ai-service unavailable?): {}", e.getMessage());
        }
    }

    // ---------------- 内部工具 ----------------

    private String cellCode(int row, int col) {
        return "GRID-R" + row + "-C" + col;
    }

    /** "GRID-R3-C2" → [3, 2]；非法编号 50000（网格由本服务生成，编号必合法） */
    private static int[] rowColOf(String cellCode) {
        try {
            String[] parts = cellCode.replace("GRID-", "").split("-");
            return new int[]{Integer.parseInt(parts[0].substring(1)), Integer.parseInt(parts[1].substring(1))};
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "invalid cell code from AI service: " + cellCode);
        }
    }

    /** 网格 (row,col) 1基 → 矩形 MultiPolygon（1km 换算经纬度增量） */
    private org.locationtech.jts.geom.MultiPolygon cellGeometry(int row, int col) {
        double dLat = cellSizeM / 111320.0;
        double dLon = cellSizeM / (111320.0 * Math.cos(Math.toRadians(originLat)));
        double minLat = originLat + (row - 1) * dLat;
        double minLon = originLon + (col - 1) * dLon;
        List<double[]> ring = List.of(
                new double[]{minLat, minLon},
                new double[]{minLat, minLon + dLon},
                new double[]{minLat + dLat, minLon + dLon},
                new double[]{minLat + dLat, minLon},
                new double[]{minLat, minLon});
        return GeoUtils.toMultiPolygon(ring);
    }

    private String levelOf(double score) {
        if (score >= highMin) {
            return "HIGH";
        }
        return score >= mediumMin ? "MEDIUM" : "LOW";
    }

    /** 区域外接框与 bbox=[minLng,minLat,maxLng,maxLat] 是否相交 */
    private static boolean intersects(RiskAreaEntity area, double[] bbox) {
        double[] env = GeoUtils.envelopeLatLon(area.getGeometry());
        if (env == null) {
            return false;
        }
        return env[1] <= bbox[2] && env[3] >= bbox[0]
                && env[0] <= bbox[3] && env[2] >= bbox[1];
    }

    private static RiskViews.AreaView toAreaView(RiskAreaEntity area) {
        return new RiskViews.AreaView(area.getId(), area.getAreaCode(), area.getAreaName(),
                GeoUtils.multiPolygonToGeoJson(area.getGeometry()), area.getRiskScore(),
                area.getRiskLevel(), area.getEvaluationTime());
    }
}

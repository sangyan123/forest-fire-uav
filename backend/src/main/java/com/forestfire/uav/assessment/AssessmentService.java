package com.forestfire.uav.assessment;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.fire.FireIncidentEntity;
import com.forestfire.uav.fire.FireIncidentRepository;
import com.forestfire.uav.fire.FirePolygonEntity;
import com.forestfire.uav.fire.FirePolygonRepository;
import com.forestfire.uav.fire.GeoUtils;
import com.forestfire.uav.media.AiServiceClient;
import org.locationtech.jts.geom.MultiPolygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
 * 灾后过火区域评估服务（F10，03号第18章一期 Change Detection mock）：
 * <ol>
 *   <li>评估对象：仅 IncidentStatus ∈ {RESOLVED, CLOSED} 可评估（用户决策 2026-10-09 方案 A），
 *       其余状态 40003 BAD_REQUEST；</li>
 *   <li>火心：最新 fire_polygon 质心作火心，无多边形回退事件坐标；
 *       lastPolygonRing 取最新 fire_polygon 外环 GeoJSON [lat,lon] 传 ai-service；</li>
 *   <li>模型参数：唯一来源 application.yml assessment.*（镜像 constants v1.7），
 *       透传 ai-service（不内嵌第二套参数）；</li>
 *   <li>幂等覆盖（idempotent_overwrite）：同事件先删旧分带+旧报告，再插新报告+3行分带，
 *       report_no 按事件稳定（RPT-INC-{incidentNo 后4位}），一事件一份现行报告。</li>
 * </ol>
 */
@Service
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    /** 可评估事件状态（用户决策 2026-10-09 方案 A） */
    private static final Set<String> ASSESSABLE_STATUSES = Set.of("RESOLVED", "CLOSED");

    /** 分带类型（BurnedAreaSeverity，与 ai-service 返回键一致） */
    private static final String[] BAND_TYPES = {"SEVERE", "MODERATE", "LIGHT"};

    @Value("${assessment.severity-ratios.severe:0.30}")
    private double ratioSevere;
    @Value("${assessment.severity-ratios.moderate:0.45}")
    private double ratioModerate;
    @Value("${assessment.severity-ratios.light:0.25}")
    private double ratioLight;
    @Value("${assessment.shrink-rate-range.min:0.75}")
    private double shrinkRateMin;
    @Value("${assessment.shrink-rate-range.max:0.95}")
    private double shrinkRateMax;
    @Value("${assessment.confidence:0.82}")
    private double confidence;
    @Value("${assessment.forest-coverage-range.min:0.55}")
    private double forestMin;
    @Value("${assessment.forest-coverage-range.max:0.80}")
    private double forestMax;
    @Value("${assessment.road-length-range.min:0}")
    private double roadMin;
    @Value("${assessment.road-length-range.max:2500}")
    private double roadMax;
    @Value("${assessment.facility-area-range.min:0}")
    private double facilityMin;
    @Value("${assessment.facility-area-range.max:800}")
    private double facilityMax;

    private final AssessmentReportRepository reportRepository;
    private final AssessmentAreaRepository areaRepository;
    private final FireIncidentRepository incidentRepository;
    private final FirePolygonRepository polygonRepository;
    private final AiServiceClient aiServiceClient;

    public AssessmentService(AssessmentReportRepository reportRepository,
                            AssessmentAreaRepository areaRepository,
                            FireIncidentRepository incidentRepository,
                            FirePolygonRepository polygonRepository,
                            AiServiceClient aiServiceClient) {
        this.reportRepository = reportRepository;
        this.areaRepository = areaRepository;
        this.incidentRepository = incidentRepository;
        this.polygonRepository = polygonRepository;
        this.aiServiceClient = aiServiceClient;
    }

    // ---------------- 评估 ----------------

    @Transactional
    public AssessmentViews.AssessSummaryView assess(UUID incidentId) {
        FireIncidentEntity incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                        "incident not found: " + incidentId));
        if (!ASSESSABLE_STATUSES.contains(incident.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "assessment only allowed for RESOLVED/CLOSED incidents, current=" + incident.getStatus());
        }

        // 火心 + lastPolygonRing：最新 fire_polygon 外环优先，回退事件坐标
        List<FirePolygonEntity> polygons =
                polygonRepository.findByIncidentIdOrderByCreatedAtAsc(incident.getId());
        List<double[]> lastRing;
        double lat;
        double lon;
        if (!polygons.isEmpty()) {
            MultiPolygon mp = polygons.get(polygons.size() - 1).getPolygon();
            var centroid = mp.getCentroid();
            lat = centroid.getY();
            lon = centroid.getX();
            lastRing = geoJsonToRing(GeoUtils.multiPolygonToGeoJson(mp));
        } else if (incident.getLatitude() != null && incident.getLongitude() != null) {
            lat = incident.getLatitude();
            lon = incident.getLongitude();
            // 回退：以事件坐标为中心构造 200m 边长方框作 lastPolygonRing
            double d = 200.0 / 111320.0;
            lastRing = List.of(
                    new double[]{lat - d, lon - d},
                    new double[]{lat - d, lon + d},
                    new double[]{lat + d, lon + d},
                    new double[]{lat + d, lon - d},
                    new double[]{lat - d, lon - d});
        } else {
            throw new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                    "no fire polygon or fire point for incident: " + incident.getIncidentNo());
        }

        AiServiceClient.AssessmentResult result =
                aiServiceClient.assessmentBurnedArea(incident.getId(), lastRing, modelParams());

        Instant now = Instant.now();
        String reportNo = reportNoOf(incident.getIncidentNo());

        // 幂等覆盖：先删旧分带（按旧报告 id）、再删旧报告
        // 注意：必须用 @Modifying 批量删除（deleteByIncidentId）+ flush，而非 JpaRepository.delete(entity)。
        // 原因：Hibernate 默认 SQL 顺序为 INSERT→UPDATE→DELETE，实体删除的 DELETE 会延迟到事务提交前，
        // 导致下方 save 新报告时 INSERT 先执行，report_no 唯一约束冲突。
        reportRepository.findByIncidentId(incident.getId()).ifPresent(old -> {
            areaRepository.deleteByReportId(old.getId());
            reportRepository.deleteByIncidentId(incident.getId());
            reportRepository.flush();
        });

        // 插新报告
        AssessmentReportEntity report = new AssessmentReportEntity();
        report.setId(UUID.randomUUID());
        report.setIncidentId(incident.getId());
        report.setReportNo(reportNo);
        report.setReportType("POST_FIRE_ASSESSMENT");
        report.setBurnedAreaSquareMeter(result.burnedAreaSquareMeter());
        report.setAffectedForestAreaSquareMeter(result.affectedForestAreaSquareMeter());
        report.setAffectedRoadLengthMeter(result.affectedRoadLengthMeter());
        report.setAffectedFacilityAreaSquareMeter(result.affectedFacilityAreaSquareMeter());
        Map<String, Object> resultMeta = new LinkedHashMap<>();
        resultMeta.put("model", "CHANGE_DETECTION_MOCK");
        resultMeta.put("severityRatios", Map.of(
                "severe", ratioSevere, "moderate", ratioModerate, "light", ratioLight));
        resultMeta.put("shrinkRateRange", Map.of("min", shrinkRateMin, "max", shrinkRateMax));
        resultMeta.put("center", List.of(lat, lon));
        report.setResult(resultMeta);
        report.setCreatedAt(now);
        report = reportRepository.save(report);

        // 插 3 行分带
        List<AssessmentViews.AreaSummaryView> bandSummaries = new ArrayList<>();
        for (AiServiceClient.AssessmentBand band : result.bands()) {
            if (band.ring() == null || band.ring().size() < 3) {
                continue;
            }
            AssessmentAreaEntity area = new AssessmentAreaEntity();
            area.setId(UUID.randomUUID());
            area.setReportId(report.getId());
            area.setAreaType(band.areaType());
            area.setGeometry(GeoUtils.toMultiPolygon(band.ring()));
            area.setAreaSquareMeter(areaSquareMeters(band.ring(), lat));
            area.setConfidence(BigDecimal.valueOf(confidence));
            area.setCreatedAt(now);
            areaRepository.save(area);
            bandSummaries.add(new AssessmentViews.AreaSummaryView(
                    band.areaType(), area.getAreaSquareMeter()));
        }

        log.info("F10 assessment: incident={} reportNo={} burnedArea={} bands={}",
                incident.getIncidentNo(), reportNo,
                Math.round(report.getBurnedAreaSquareMeter() != null ? report.getBurnedAreaSquareMeter() : 0),
                bandSummaries.size());
        return new AssessmentViews.AssessSummaryView(
                report.getId(), report.getIncidentId(), report.getReportNo(),
                report.getBurnedAreaSquareMeter(), report.getAffectedForestAreaSquareMeter(),
                report.getAffectedRoadLengthMeter(), report.getAffectedFacilityAreaSquareMeter(),
                bandSummaries, now);
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<AssessmentViews.ReportView> list(UUID incidentId) {
        List<AssessmentReportEntity> reports = (incidentId == null)
                ? reportRepository.findAllByOrderByCreatedAtDesc()
                : reportRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
        List<AssessmentViews.ReportView> views = new ArrayList<>();
        for (AssessmentReportEntity report : reports) {
            List<AssessmentViews.AreaSummaryView> bands = areaRepository
                    .findByReportIdOrderByAreaSquareMeterDesc(report.getId()).stream()
                    .map(a -> new AssessmentViews.AreaSummaryView(a.getAreaType(), a.getAreaSquareMeter()))
                    .toList();
            views.add(new AssessmentViews.ReportView(
                    report.getId(), report.getIncidentId(), report.getReportNo(),
                    report.getReportType(), report.getBurnedAreaSquareMeter(),
                    report.getAffectedForestAreaSquareMeter(), report.getAffectedRoadLengthMeter(),
                    report.getAffectedFacilityAreaSquareMeter(), bands, report.getCreatedAt()));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public AssessmentViews.ReportDetailView detail(UUID reportId) {
        AssessmentReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                        "assessment report not found: " + reportId));
        List<AssessmentViews.AreaView> bands = areaRepository
                .findByReportIdOrderByAreaSquareMeterDesc(report.getId()).stream()
                .map(a -> new AssessmentViews.AreaView(a.getAreaType(),
                        GeoUtils.multiPolygonToGeoJson(a.getGeometry()),
                        a.getAreaSquareMeter(), a.getConfidence()))
                .toList();
        return new AssessmentViews.ReportDetailView(
                report.getId(), report.getIncidentId(), report.getReportNo(),
                report.getReportType(), report.getBurnedAreaSquareMeter(),
                report.getAffectedForestAreaSquareMeter(), report.getAffectedRoadLengthMeter(),
                report.getAffectedFacilityAreaSquareMeter(), bands, report.getCreatedAt());
    }

    // ---------------- 内部工具 ----------------

    private Map<String, Object> modelParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("shrinkRateMin", shrinkRateMin);
        params.put("shrinkRateMax", shrinkRateMax);
        params.put("severityRatios", Map.of(
                "severe", ratioSevere, "moderate", ratioModerate, "light", ratioLight));
        params.put("confidence", confidence);
        params.put("forestMin", forestMin);
        params.put("forestMax", forestMax);
        params.put("roadMax", roadMax);
        params.put("facilityMax", facilityMax);
        return params;
    }

    /** report_no 规则：RPT-INC-{incidentNo 后4位}，按事件稳定（幂等覆盖时不变） */
    private static String reportNoOf(String incidentNo) {
        String tail = incidentNo == null || incidentNo.length() < 4
                ? "0000" : incidentNo.substring(incidentNo.length() - 4);
        return "RPT-INC-" + tail;
    }

    /** GeoJSON [lat,lon] List → double[][lat,lon] 数组（ai-service 入参格式） */
    private static List<double[]> geoJsonToRing(List<List<Double>> geoJson) {
        if (geoJson == null || geoJson.size() < 3) {
            return List.of();
        }
        List<double[]> ring = new ArrayList<>();
        for (List<Double> p : geoJson) {
            if (p != null && p.size() >= 2) {
                ring.add(new double[]{p.get(0), p.get(1)});
            }
        }
        return ring;
    }

    /** shoelace 面积（平方米），与 ai-service _shoelace_area_m2 同模式（后端侧复算分带面积） */
    private static double areaSquareMeters(List<double[]> ring, double refLat) {
        if (ring == null || ring.size() < 3) {
            return 0.0;
        }
        List<double[]> pts = ring.get(0)[0] == ring.get(ring.size() - 1)[0]
                && ring.get(0)[1] == ring.get(ring.size() - 1)[1]
                ? ring : new ArrayList<>(ring) {{ add(ring.get(0)); }};
        if (pts.size() < 4) {
            return 0.0;
        }
        double mPerDegLat = 111320.0;
        double mPerDegLon = 111320.0 * Math.cos(Math.toRadians(refLat));
        double s = 0.0;
        for (int i = 0; i < pts.size() - 1; i++) {
            double y0 = pts.get(i)[0], x0 = pts.get(i)[1];
            double y1 = pts.get(i + 1)[0], x1 = pts.get(i + 1)[1];
            s += x0 * y1 - x1 * y0;
        }
        return Math.round(Math.abs(s) / 2.0 * mPerDegLat * mPerDegLon * 10.0) / 10.0;
    }
}

package com.forestfire.uav.fire;

import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.media.AiServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 火场分析服务（F05 分割 + F06 跟踪，D3）：
 * <ol>
 *   <li>POST /api/v1/fire/incidents/{id}/analysis — 仅 CONFIRMED/TRACKING 可用（否则 40003）：
 *       取最新 fire_point 为 center，growthStep=该事件已有 fire_polygon 行数，
 *       调 AI segmentation（半径 150m+90m/轮递增）落 fire_polygon（geometry(MultiPolygon,4326)，
 *       列类型以 DDL 为准），调 AI tracking 落 fire_track（event_time=now）；</li>
 *   <li>GET /api/v1/fire/incidents/{id}/polygons — created_at 升序，几何给 GeoJSON [lat,lon]
 *       坐标数组（Leaflet 直接可用）；radiusMeters 由面积派生（DDL 无半径列）；</li>
 *   <li>GET /api/v1/fire/incidents/{id}/tracking — 最新一条；无记录返回空对象 {}
 *       （约定：前端不视为错误，区别于事件不存在的 40401）。</li>
 * </ol>
 */
@Service
public class FireAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FireAnalysisService.class);

    /** 允许火场分析的状态（任务书口径：CONFIRMED/TRACKING） */
    private static final Set<String> ANALYSIS_ALLOWED_STATUSES = Set.of("CONFIRMED", "TRACKING");

    private final FireIncidentRepository incidentRepository;
    private final FirePointRepository pointRepository;
    private final FirePolygonRepository polygonRepository;
    private final FireTrackRepository trackRepository;
    private final AiServiceClient aiServiceClient;

    public FireAnalysisService(FireIncidentRepository incidentRepository,
                               FirePointRepository pointRepository,
                               FirePolygonRepository polygonRepository,
                               FireTrackRepository trackRepository,
                               AiServiceClient aiServiceClient) {
        this.incidentRepository = incidentRepository;
        this.pointRepository = pointRepository;
        this.polygonRepository = polygonRepository;
        this.trackRepository = trackRepository;
        this.aiServiceClient = aiServiceClient;
    }

    // ---------------- 视图 ----------------

    /** 单轮分割视图（polygon 为 GeoJSON [lat,lon] 坐标数组） */
    public record PolygonView(Double radiusMeters, Double areaSquareMeters, List<List<Double>> polygon) {
    }

    /** 跟踪视图 */
    public record TrackingView(UUID id, UUID incidentId, Instant eventTime,
                               Double direction, Double speed, Double areaSquareMeters,
                               Double areaGrowthRate, String trend) {
    }

    /** POST analysis 响应：{polygon:{radiusMeters,areaSquareMeters,polygon}, tracking:{...}, growthStep} */
    public record AnalysisResult(PolygonView polygon, TrackingView tracking, int growthStep) {
    }

    /** GET polygons 历史项（radiusMeters 由面积派生 r=√(A/π)，DDL 无半径列） */
    public record PolygonHistoryView(UUID id, UUID incidentId, Double radiusMeters,
                                     Double areaSquareMeters, Double perimeterMeters,
                                     java.math.BigDecimal confidence,
                                     Instant detectedAt, Instant createdAt,
                                     List<List<Double>> polygon) {
    }

    // ---------------- analysis ----------------

    @Transactional
    public AnalysisResult analyze(UUID incidentId) {
        FireIncidentEntity incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATH_NOT_FOUND,
                        "incident not found: " + incidentId));
        String status = incident.getStatus();
        if (!ANALYSIS_ALLOWED_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "analysis only allowed in CONFIRMED/TRACKING, current=" + status);
        }
        FirePointEntity center = pointRepository
                .findByIncidentIdOrderByDetectedAtDesc(incidentId).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND,
                        "no fire point for incident: " + incidentId));
        double lat = center.getLatitude();
        double lon = center.getLongitude();
        int growthStep = (int) polygonRepository.countByIncidentId(incidentId);
        Instant now = Instant.now();

        // ---- F05 分割 → fire_polygon ----
        AiServiceClient.SegmentationResult seg = aiServiceClient.segment(
                UUID.randomUUID(), incidentId.toString(), lat, lon, growthStep);
        if (seg.ring() == null || seg.ring().size() < 3) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "AI segmentation returned invalid polygon ring");
        }
        FirePolygonEntity polygon = new FirePolygonEntity();
        polygon.setId(UUID.randomUUID());                               // id UUID PK（应用生成）
        polygon.setIncidentId(incident.getId());                        // incident_id NOT NULL
        polygon.setPolygon(GeoUtils.toMultiPolygon(seg.ring()));        // polygon NOT NULL（MultiPolygon）
        polygon.setAreaSquareMeter(seg.areaSquareMeters());             // area_square_meter
        polygon.setPerimeterMeter(GeoUtils.perimeterMeters(seg.ring())); // perimeter_meter
        polygon.setConfidence(null);                                    // confidence（F05 不返回）
        polygon.setSourceMediaId(null);                                 // source_media_id（无媒体输入）
        polygon.setDetectedAt(now);                                     // detected_at NOT NULL
        polygon.setCreatedAt(now);                                      // created_at NOT NULL
        polygonRepository.save(polygon);

        // ---- F06 跟踪 → fire_track ----
        AiServiceClient.TrackingResult trk = aiServiceClient.track(UUID.randomUUID(), incidentId.toString());
        FireTrackEntity track = new FireTrackEntity();
        track.setId(UUID.randomUUID());                                 // id UUID PK（应用生成）
        track.setIncidentId(incident.getId());                          // incident_id NOT NULL
        track.setEventTime(now);                                        // event_time NOT NULL
        track.setCenter(GeoUtils.toPoint(lat, lon));                    // center
        track.setDirection(trk.direction());                            // direction
        track.setSpeed(trk.speed());                                    // speed
        track.setAreaSquareMeter(seg.areaSquareMeters());               // area_square_meter（同轮 F05）
        track.setAreaGrowthRate(trk.areaGrowthRate());                  // area_growth_rate
        track.setTrend(trk.trend());                                    // trend
        track.setConfidence(null);                                      // confidence（F06 不返回）
        track.setSourceUavId(incident.getSourceUavId());                // source_uav_id
        track.setCreatedAt(now);                                        // created_at NOT NULL
        trackRepository.save(track);

        log.info("analysis incident {} step={} radius={} area={} trend={}",
                incident.getIncidentNo(), growthStep, seg.radiusMeters(),
                seg.areaSquareMeters(), trk.trend());

        return new AnalysisResult(
                new PolygonView(seg.radiusMeters(), seg.areaSquareMeters(), toGeoJsonRing(seg.ring())),
                toTrackingView(track),
                growthStep);
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<PolygonHistoryView> listPolygons(UUID incidentId) {
        requireIncident(incidentId);
        return polygonRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(p -> new PolygonHistoryView(
                        p.getId(), p.getIncidentId(),
                        GeoUtils.radiusFromArea(p.getAreaSquareMeter()),
                        p.getAreaSquareMeter(), p.getPerimeterMeter(),
                        p.getConfidence(), p.getDetectedAt(), p.getCreatedAt(),
                        GeoUtils.multiPolygonToGeoJson(p.getPolygon())))
                .toList();
    }

    /**
     * 最新跟踪。约定：无记录返回空对象 {}（前端不视为错误；事件不存在仍为 40401）。
     */
    @Transactional(readOnly = true)
    public Object latestTracking(UUID incidentId) {
        requireIncident(incidentId);
        Optional<FireTrackEntity> latest = trackRepository
                .findFirstByIncidentIdOrderByEventTimeDesc(incidentId);
        if (latest.isEmpty()) {
            return Map.of(); // 约定：无记录返回空对象 {}（前端不视为错误）
        }
        return toTrackingView(latest.get());
    }

    // ---------------- 内部工具 ----------------

    private void requireIncident(UUID incidentId) {
        if (!incidentRepository.existsById(incidentId)) {
            throw new BusinessException(ErrorCode.PATH_NOT_FOUND,
                    "incident not found: " + incidentId);
        }
    }

    private FireAnalysisService.TrackingView toTrackingView(FireTrackEntity t) {
        return new TrackingView(t.getId(), t.getIncidentId(), t.getEventTime(),
                t.getDirection(), t.getSpeed(), t.getAreaSquareMeter(),
                t.getAreaGrowthRate(), t.getTrend());
    }

    /** [lat,lon] 环 → [[lat,lon],...]（响应/GeoJSON 用） */
    private static List<List<Double>> toGeoJsonRing(List<double[]> ring) {
        List<List<Double>> out = new ArrayList<>();
        for (double[] p : ring) {
            out.add(List.of(p[0], p[1]));
        }
        return out;
    }
}

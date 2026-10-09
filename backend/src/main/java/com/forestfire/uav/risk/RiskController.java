package com.forestfire.uav.risk;

import com.forestfire.uav.common.ApiResponse;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.mission.MissionViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 火情风险检测页端点（F07+F11+F08，03号第91.4节，openapi Risk tag 9 端点）：
 * <ul>
 *   <li>GET  /api/v1/risk/areas?level=&bbox=          — 风险区域列表/图层</li>
 *   <li>GET  /api/v1/risk/areas/{areaId}              — 区域详情（五因子分项）</li>
 *   <li>POST /api/v1/risk/assess                      — 触发重评估（F07）</li>
 *   <li>GET  /api/v1/predictions/latest?incidentId=   — 最新预测（F11，30/60min 两档）</li>
 *   <li>POST /api/v1/predictions/run                  — 手动重跑预测</li>
 *   <li>GET  /api/v1/patrol-suggestions?status=       — 建议列表（F08）</li>
 *   <li>POST /api/v1/patrol-suggestions/generate      — 生成巡检建议（top N）</li>
 *   <li>POST /api/v1/patrol-suggestions/{id}/dispatch — 下发成 RISK_PATROL 任务</li>
 *   <li>POST /api/v1/patrol-suggestions/{id}/dismiss  — 驳回建议</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class RiskController {

    private static final Set<String> RISK_LEVELS = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> SUGGESTION_STATUSES =
            Set.of("SUGGESTED", "DISPATCHED", "DISMISSED", "EXPIRED");

    private final RiskService riskService;
    private final PredictionService predictionService;
    private final PatrolSuggestionService suggestionService;

    public RiskController(RiskService riskService,
                          PredictionService predictionService,
                          PatrolSuggestionService suggestionService) {
        this.riskService = riskService;
        this.predictionService = predictionService;
        this.suggestionService = suggestionService;
    }

    // ---------------- F07 风险区域 ----------------

    @GetMapping("/risk/areas")
    public ApiResponse<List<RiskViews.AreaView>> areas(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String bbox) {
        return ApiResponse.ok(riskService.list(normalizeLevel(level), parseBbox(bbox)));
    }

    @GetMapping("/risk/areas/{areaId}")
    public ApiResponse<RiskViews.AreaDetailView> areaDetail(@PathVariable String areaId) {
        return ApiResponse.ok(riskService.detail(parseUuid(areaId, "areaId")));
    }

    @PostMapping("/risk/assess")
    public ApiResponse<RiskViews.AssessSummary> assess() {
        return ApiResponse.ok(riskService.assess());
    }

    // ---------------- F11 火势预测 ----------------

    @GetMapping("/predictions/latest")
    public ApiResponse<List<RiskViews.PredictionView>> latestPrediction(
            @RequestParam(name = "incidentId", required = false) String incidentId) {
        return ApiResponse.ok(predictionService.latest(parseUuidOr(incidentId)));
    }

    @PostMapping("/predictions/run")
    public ApiResponse<List<RiskViews.PredictionView>> runPrediction(
            @RequestBody(required = false) Map<String, Object> body) {
        UUID incidentId = body == null ? null : parseUuidOr(body.get("incidentId"));
        return ApiResponse.ok(predictionService.run(incidentId));
    }

    // ---------------- F08 巡检建议 ----------------

    @GetMapping("/patrol-suggestions")
    public ApiResponse<List<RiskViews.SuggestionView>> suggestions(
            @RequestParam(required = false) String status) {
        String normalized = status == null || status.isBlank() ? null
                : status.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !SUGGESTION_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "invalid status: " + status + " (allowed: " + SUGGESTION_STATUSES + ")");
        }
        return ApiResponse.ok(suggestionService.list(normalized));
    }

    @PostMapping("/patrol-suggestions/generate")
    public ApiResponse<List<RiskViews.SuggestionView>> generate(
            @RequestBody(required = false) PatrolSuggestionService.GenerateRequest request) {
        return ApiResponse.ok(suggestionService.generate(request));
    }

    @PostMapping("/patrol-suggestions/{suggestionId}/dispatch")
    public ApiResponse<MissionViews.MissionView> dispatch(@PathVariable String suggestionId) {
        return ApiResponse.ok(suggestionService.dispatch(parseUuid(suggestionId, "suggestionId")));
    }

    @PostMapping("/patrol-suggestions/{suggestionId}/dismiss")
    public ApiResponse<RiskViews.SuggestionView> dismiss(@PathVariable String suggestionId) {
        return ApiResponse.ok(suggestionService.dismiss(parseUuid(suggestionId, "suggestionId")));
    }

    // ---------------- 内部工具 ----------------

    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String normalized = level.trim().toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "invalid level: " + level + " (allowed: " + RISK_LEVELS + ")");
        }
        return normalized;
    }

    /** "minLng,minLat,maxLng,maxLat" → [minLng,minLat,maxLng,maxLat]；非法 40001 */
    private static double[] parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return null;
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "bbox must be minLng,minLat,maxLng,maxLat");
        }
        try {
            return new double[]{
                    Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim())};
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bbox values must be numbers");
        }
    }

    private static UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    name + " must be a UUID: " + value);
        }
    }

    /** 宽容解析：null/空/非 UUID → null（predictions 的 incidentId 可缺省） */
    private static UUID parseUuidOr(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return parseUuid(text, "incidentId");
    }
}

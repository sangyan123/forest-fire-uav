package com.forestfire.uav.assessment;

import com.forestfire.uav.common.ApiResponse;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 灾后情况检测页端点（F10，03号第18.7节，openapi Assessment tag 3 端点）：
 * <ul>
 *   <li>POST /api/v1/assessment/burned-area       — 触发评估（入参 {incidentId}，仅 RESOLVED/CLOSED）</li>
 *   <li>GET  /api/v1/assessment/reports?incidentId= — 评估报告列表（按 created_at 倒序，含分带摘要）</li>
 *   <li>GET  /api/v1/assessment/reports/{id}        — 报告详情（含三分带 GeoJSON）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class AssessmentController {

    private final AssessmentService assessmentService;

    public AssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    @PostMapping("/assessment/burned-area")
    public ApiResponse<AssessmentViews.AssessSummaryView> burnedArea(
            @RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("incidentId");
        UUID incidentId = parseUuidOr(raw);
        if (incidentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "incidentId is required");
        }
        return ApiResponse.ok(assessmentService.assess(incidentId));
    }

    @GetMapping("/assessment/reports")
    public ApiResponse<List<AssessmentViews.ReportView>> reports(
            @RequestParam(required = false) String incidentId) {
        UUID id = parseUuidOr(incidentId);
        return ApiResponse.ok(assessmentService.list(id));
    }

    @GetMapping("/assessment/reports/{reportId}")
    public ApiResponse<AssessmentViews.ReportDetailView> reportDetail(
            @PathVariable String reportId) {
        return ApiResponse.ok(assessmentService.detail(parseUuid(reportId, "reportId")));
    }

    // ---------------- 内部工具 ----------------

    private static UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    name + " must be a UUID: " + value);
        }
    }

    /** 宽容解析：null/空/非 UUID → null（reports 的 incidentId 可缺省） */
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

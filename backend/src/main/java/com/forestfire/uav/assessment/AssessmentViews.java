package com.forestfire.uav.assessment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 灾后情况检测页视图集合（03号第18.7节 API 契约，字段名与 openapi.yaml
 * AssessSummary/AssessmentReport/AssessmentReportDetail/AssessmentArea 一致）。
 */
public final class AssessmentViews {

    private AssessmentViews() {
    }

    /** 分带摘要（列表项用，不含 geometry） */
    public record AreaSummaryView(String areaType, Double areaSquareMeter) {
    }

    /** 分带完整数据（详情用，含 geometry GeoJSON） */
    public record AreaView(String areaType, List<List<Double>> geometry,
                           Double areaSquareMeter, BigDecimal confidence) {
    }

    /** 评估摘要（POST /assessment/burned-area 响应） */
    public record AssessSummaryView(UUID reportId, UUID incidentId, String reportNo,
                                    Double burnedAreaSquareMeter,
                                    Double affectedForestAreaSquareMeter,
                                    Double affectedRoadLengthMeter,
                                    Double affectedFacilityAreaSquareMeter,
                                    List<AreaSummaryView> severityBands,
                                    Instant assessedAt) {
    }

    /** 评估报告（GET /assessment/reports 列表项） */
    public record ReportView(UUID reportId, UUID incidentId, String reportNo,
                              String reportType,
                              Double burnedAreaSquareMeter,
                              Double affectedForestAreaSquareMeter,
                              Double affectedRoadLengthMeter,
                              Double affectedFacilityAreaSquareMeter,
                              List<AreaSummaryView> severityBands,
                              Instant createdAt) {
    }

    /** 报告详情（GET /assessment/reports/{id}，severityBands 含 geometry） */
    public record ReportDetailView(UUID reportId, UUID incidentId, String reportNo,
                                   String reportType,
                                   Double burnedAreaSquareMeter,
                                   Double affectedForestAreaSquareMeter,
                                   Double affectedRoadLengthMeter,
                                   Double affectedFacilityAreaSquareMeter,
                                   List<AreaView> severityBands,
                                   Instant createdAt) {
    }
}

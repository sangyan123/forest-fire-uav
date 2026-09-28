package com.forestfire.uav.fire;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 火情域视图（列表/详情/火点/检测/核验）。字段只含前端演示需要的列，
 * PostGIS geometry 列不外露（坐标已有独立 lat/lon 列）。
 */
public final class FireViews {

    private FireViews() {
    }

    /** GET /api/v1/fire/incidents 列表项 */
    public record IncidentSummary(
            UUID id,
            String incidentNo,
            String title,
            String status,
            String level,
            String scenarioType,
            Double latitude,
            Double longitude,
            BigDecimal latestConfidence,
            Integer detectionCount,
            Instant firstDetectedAt,
            String verificationStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** 检测概要 */
    public record DetectionView(
            UUID id,
            String detectionType,
            BigDecimal confidence,
            Map<String, Object> bbox,
            Double latitude,
            Double longitude,
            UUID mediaId,
            Instant detectionTime
    ) {
    }

    /** 火点视图（GET /api/v1/fire/points/{id} 与详情内嵌） */
    public record PointView(
            UUID id,
            UUID incidentId,
            UUID detectionId,
            Double latitude,
            Double longitude,
            Double altitude,
            Double positionErrorRadius,
            String locationMethod,
            BigDecimal confidence,
            UUID sourceUavId,
            Instant detectedAt,
            Instant createdAt
    ) {
    }

    /** 核验视图 */
    public record VerificationView(
            UUID id,
            UUID incidentId,
            String result,
            BigDecimal finalScore,
            BigDecimal rgbScore,
            BigDecimal thermalScore,
            BigDecimal temporalScore,
            BigDecimal spatialScore,
            String incidentStatus,
            Instant verifiedAt,
            String scenarioType,
            String evidenceSource
    ) {
    }

    /** GET /api/v1/fire/incidents/{id} 详情 */
    public record IncidentDetail(
            UUID id,
            String incidentNo,
            String title,
            String status,
            String level,
            String scenarioType,
            Double latitude,
            Double longitude,
            Instant firstDetectedAt,
            Instant confirmedAt,
            Instant resolvedAt,
            String verificationStatus,
            Boolean falseAlarm,
            String description,
            Map<String, Object> extra,
            Instant createdAt,
            Instant updatedAt,
            Integer polygonsCount,
            List<PointView> firePoints,
            List<DetectionView> detections,
            VerificationView latestVerification
    ) {
    }
}

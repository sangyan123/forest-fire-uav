package com.forestfire.uav.mission;

import com.forestfire.uav.dispatch.DispatchRecordEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务域视图（列表/详情/航点/调度）。
 */
public final class MissionViews {

    private MissionViews() {
    }

    /** 航点视图 */
    public record WaypointView(
            UUID id,
            Integer sequenceNo,
            Double latitude,
            Double longitude,
            Double altitude,
            Double speed,
            Double heading,
            Map<String, Object> action
    ) {
    }

    /** 调度概要 */
    public record DispatchView(
            UUID id,
            UUID missionId,
            UUID incidentId,
            String selectedUav,
            String algorithmType,
            Double score,
            Map<String, Object> factors,
            Double estimatedDistance,
            Integer estimatedArrivalSeconds,
            String result,
            Instant decisionTime
    ) {
    }

    /** 任务视图（列表项与详情共用；详情附 waypoints/latestDispatch） */
    public record MissionView(
            UUID id,
            String missionNo,
            String missionType,
            UUID incidentId,
            String incidentNo,
            String assignedUav,
            String priority,
            String status,
            Instant actualStartAt,
            Instant actualEndAt,
            Double targetLatitude,
            Double targetLongitude,
            Double targetAltitude,
            String targetAltitudeMode,
            Instant createdAt,
            Instant updatedAt,
            List<WaypointView> waypoints,
            DispatchView latestDispatch
    ) {
    }

    /** POST /api/v1/missions/{id}/start 响应 */
    public record MissionStartResult(
            UUID missionId,
            String missionNo,
            String missionStatus,
            UUID dispatchId,
            String uavId,
            Double score,
            UUID commandId,
            String commandNo
    ) {
    }

    /** DispatchRecordEntity → 视图 */
    public static DispatchView toDispatchView(DispatchRecordEntity r, String uavCode) {
        return new DispatchView(
                r.getId(), r.getMissionId(), r.getIncidentId(), uavCode,
                r.getAlgorithmType(), r.getScore(), r.getFactors(),
                r.getEstimatedDistance(), r.getEstimatedArrivalSeconds(),
                r.getResult(), r.getDecisionTime());
    }
}

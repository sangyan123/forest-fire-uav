package com.forestfire.uav.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.forestfire.uav.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥测接入端点：POST /api/v1/uavs/{uavId}/telemetry-ingest。
 * body 为整条 UAV_STATE 消息 JSON。
 */
@RestController
@RequestMapping("/api/v1/uavs/{uavId}")
public class TelemetryIngestController {

    private final TelemetryIngestService ingestService;

    public TelemetryIngestController(TelemetryIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/telemetry-ingest")
    public ApiResponse<TelemetryIngestService.IngestResult> ingest(
            @PathVariable String uavId,
            @RequestBody JsonNode message) {
        return ApiResponse.ok(ingestService.ingest(uavId, message));
    }
}

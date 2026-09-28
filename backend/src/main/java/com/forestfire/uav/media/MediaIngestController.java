package com.forestfire.uav.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.forestfire.uav.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体接入端点：
 * <ul>
 *   <li>POST /api/v1/uavs/{uavId}/media-ingest — gateway 转发 UAV_MEDIA 原文
 *       （header X-Service-Name: uav-gateway，uavId 取路径），触发火情自动发现闭环</li>
 * </ul>
 * 入参为整条 UAV_MEDIA 消息 JSON（05号第37章结构），JsonNode 宽松解析。
 */
@RestController
@RequestMapping("/api/v1")
public class MediaIngestController {

    private final MediaIngestService mediaIngestService;

    public MediaIngestController(MediaIngestService mediaIngestService) {
        this.mediaIngestService = mediaIngestService;
    }

    @PostMapping("/uavs/{uavId}/media-ingest")
    public ApiResponse<MediaIngestService.MediaIngestResult> ingest(
            @PathVariable String uavId,
            @RequestBody JsonNode message) {
        return ApiResponse.ok(mediaIngestService.ingest(uavId, message));
    }
}

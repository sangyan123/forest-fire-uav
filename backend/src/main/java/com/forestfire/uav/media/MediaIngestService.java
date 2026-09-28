package com.forestfire.uav.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.forestfire.uav.common.BusinessException;
import com.forestfire.uav.common.ErrorCode;
import com.forestfire.uav.device.UavDeviceEntity;
import com.forestfire.uav.device.UavDeviceRepository;
import com.forestfire.uav.fire.FireDetectionEntity;
import com.forestfire.uav.fire.FireDetectionRepository;
import com.forestfire.uav.fire.FireIncidentService;
import com.forestfire.uav.fire.FirePointEntity;
import com.forestfire.uav.fire.FirePointRepository;
import com.forestfire.uav.fire.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 媒体接入服务：POST /api/v1/uavs/{uavId}/media-ingest（gateway 转发 UAV_MEDIA 原文，
 * header X-Service-Name: uav-gateway，uavId 取路径）。
 *
 * <p>火情自动发现闭环（RGB_IMAGE）：</p>
 * <ol>
 *   <li>存 media_file（协议 mediaId 落 metadata.protocolMediaId）；</li>
 *   <li>F01 检测：POST ai-service /ai/v1/detection（taskId 随机 UUID，mediaId=media_file.id），
 *       最高 confidence ≥ T_alert(0.60, fire-detection-v1.yaml) → 落 fire_detection；</li>
 *   <li>F03 定位：POST /ai/v1/localization（传 media.position）→ 落 fire_point
 *       （accuracy→position_error_radius，method→location_method）；</li>
 *   <li>去重/事件登记：FireIncidentService.attachPointToIncident（100m/120s）。</li>
 * </ol>
 * THERMAL_IMAGE 等其余类型只存媒体记录不触发检测。AI 服务不可达时优雅降级：
 * 保留已成功的落库（媒体/检测），firePointId/incidentId 置空并告警日志，不让 gateway 收 500。
 */
@Service
public class MediaIngestService {

    private static final Logger log = LoggerFactory.getLogger(MediaIngestService.class);

    private final MediaFileRepository mediaFileRepository;
    private final FireDetectionRepository detectionRepository;
    private final FirePointRepository pointRepository;
    private final FireIncidentService fireIncidentService;
    private final UavDeviceRepository deviceRepository;
    private final AiServiceClient aiServiceClient;

    public MediaIngestService(MediaFileRepository mediaFileRepository,
                              FireDetectionRepository detectionRepository,
                              FirePointRepository pointRepository,
                              FireIncidentService fireIncidentService,
                              UavDeviceRepository deviceRepository,
                              AiServiceClient aiServiceClient) {
        this.mediaFileRepository = mediaFileRepository;
        this.detectionRepository = detectionRepository;
        this.pointRepository = pointRepository;
        this.fireIncidentService = fireIncidentService;
        this.deviceRepository = deviceRepository;
        this.aiServiceClient = aiServiceClient;
    }

    /**
     * ingest 结果（任务书要求 {detectionId, firePointId, incidentId, incidentStatus}，外加溯源字段）。
     * D2 误报演示线：scenarioType = media.metadata.scenarioType 归一值（FIRE/FALSE_ALARM，缺省 FIRE）。
     */
    public record MediaIngestResult(
            String uavId,
            String protocolMediaId,
            UUID mediaFileId,
            UUID detectionId,
            UUID firePointId,
            UUID incidentId,
            String incidentStatus,
            boolean fireDetected,
            String scenarioType
    ) {
    }

    @Transactional
    public MediaIngestResult ingest(String uavId, JsonNode message) {
        if (message == null || !message.path("media").isObject() || message.path("media").isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "media is required");
        }
        JsonNode media = message.path("media");
        String mediaType = media.path("type").asText(null);
        if (mediaType == null || mediaType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "media.type is required");
        }
        String protocolMediaId = media.path("mediaId").asText(null);
        Instant capturedAt = parseInstant(media.path("capturedAt").asText(null), Instant.now());
        UavDeviceEntity device = resolveDevice(uavId, message);
        // D2 误报演示线：场景提示随媒体传入（media.metadata.scenarioType），缺省 FIRE
        String scenarioType = FireIncidentService.normalizeScenarioType(
                media.path("metadata").path("scenarioType").asText(null));

        // ---- 1. 存 media_file ----
        MediaFileEntity mf = saveMediaFile(device, message, media, mediaType, capturedAt, scenarioType);

        // ---- THERMAL_IMAGE 等非 RGB：只存媒体记录，不触发检测 ----
        if (!"RGB_IMAGE".equalsIgnoreCase(mediaType)) {
            log.debug("media {} type={} stored without detection pipeline",
                    mf.getId(), mediaType);
            return new MediaIngestResult(uavId, protocolMediaId, mf.getId(),
                    null, null, null, null, false, scenarioType);
        }

        // ---- 2. F01 检测 ----
        List<AiServiceClient.DetectionItem> detections;
        try {
            detections = aiServiceClient.detect(UUID.randomUUID(), mf.getId());
        } catch (Exception e) {
            log.warn("AI detection failed for media {}: {}", mf.getId(), e.getMessage());
            return new MediaIngestResult(uavId, protocolMediaId, mf.getId(),
                    null, null, null, null, false, scenarioType);
        }
        AiServiceClient.DetectionItem best = detections.stream()
                .reduce((a, b) -> b.confidence() >= a.confidence() ? b : a)
                .orElse(null);
        if (best == null || best.confidence() < FireIncidentService.T_ALERT) {
            // 未过 T_alert（0.60）：只留媒体记录
            return new MediaIngestResult(uavId, protocolMediaId, mf.getId(),
                    null, null, null, null, false, scenarioType);
        }

        // ---- fire_detection 行 ----
        FireDetectionEntity detection = new FireDetectionEntity();
        detection.setId(UUID.randomUUID());                             // id UUID PK（应用生成）
        detection.setUavId(device.getId());                             // uav_id
        detection.setMediaId(mf.getId());                               // media_id → media_file.id
        detection.setDetectionType(best.className() == null
                ? "SMOKE" : best.className().trim().toUpperCase());     // detection_type NOT NULL
        detection.setConfidence(BigDecimal.valueOf(best.confidence())); // confidence
        detection.setBbox(FireIncidentService.bboxToMap(best.bbox()));  // bbox JSONB
        detection.setDetectionTime(capturedAt);                         // detection_time NOT NULL
        detection.setTemporalConfirmed(false);                          // temporal_confirmed
        detection.setCreatedAt(Instant.now());                          // created_at NOT NULL
        detection = detectionRepository.save(detection);

        // ---- 3. F03 定位 → fire_point 行 ----
        Double shotLat = doubleOrNull(media.path("position"), "latitude");
        Double shotLon = doubleOrNull(media.path("position"), "longitude");
        if (shotLat == null || shotLon == null) {
            // 无拍摄坐标无法定位（AI mock 会回落默认坐标，属脏数据），仅保留检测记录
            log.warn("media {} has no position, skip fire point/incident", mf.getId());
            return new MediaIngestResult(uavId, protocolMediaId, mf.getId(),
                    detection.getId(), null, null, null, true, scenarioType);
        }
        AiServiceClient.LocalizationResult loc;
        try {
            loc = aiServiceClient.localize(UUID.randomUUID(), shotLat, shotLon);
        } catch (Exception e) {
            // 定位失败优雅降级：以拍摄点 GNSS 坐标为火点位置（method 兜底）
            log.warn("AI localization failed for media {}: {}", mf.getId(), e.getMessage());
            loc = new AiServiceClient.LocalizationResult(shotLat, shotLon, null, "MANUAL");
        }
        Double lat = loc.latitude() != null ? loc.latitude() : shotLat;
        Double lon = loc.longitude() != null ? loc.longitude() : shotLon;
        String method = loc.method() != null && !loc.method().isBlank()
                ? loc.method().trim().toUpperCase() : "RAY_GROUND_INTERSECTION";

        FirePointEntity point = new FirePointEntity();
        point.setId(UUID.randomUUID());                                 // id UUID PK（应用生成）
        point.setDetectionId(detection.getId());                        // detection_id
        point.setLatitude(lat);                                         // latitude NOT NULL
        point.setLongitude(lon);                                        // longitude NOT NULL
        point.setAltitude(doubleOrNull(media.path("position"), "altitude")); // altitude
        point.setGeometry(GeoUtils.toPoint(lat, lon));                  // geometry NOT NULL
        point.setPositionErrorRadius(loc.accuracy());                   // position_error_radius ← accuracy
        point.setLocationMethod(method);                                // location_method NOT NULL
        point.setConfidence(detection.getConfidence());                 // confidence
        point.setSourceUavId(device.getId());                           // source_uav_id
        point.setDetectedAt(capturedAt);                                // detected_at NOT NULL
        point.setCreatedAt(Instant.now());                              // created_at NOT NULL
        point = pointRepository.save(point);

        // 回填检测记录的定位坐标（可空列）
        detection.setLatitude(lat);
        detection.setLongitude(lon);
        detection.setGeometry(point.getGeometry());
        detectionRepository.save(detection);

        // ---- 4. 去重 / 事件登记（100m/120s；D2：scenarioType 随之写入 incident.extra） ----
        FireIncidentService.IncidentAttachResult attach =
                fireIncidentService.attachPointToIncident(point, detection.getConfidence(), scenarioType);

        return new MediaIngestResult(uavId, protocolMediaId, mf.getId(),
                detection.getId(), point.getId(), attach.incident().getId(),
                attach.incident().getStatus(), true, scenarioType);
    }

    // ---------------- 内部工具 ----------------

    /** 按 device_code 查设备；不存在则建最小桩（与遥测接入同一 upsert 习惯，保证 uav_id 引用完整） */
    private UavDeviceEntity resolveDevice(String uavId, JsonNode message) {
        UavDeviceEntity device = deviceRepository.findByDeviceCode(uavId).orElse(null);
        if (device != null) {
            return device;
        }
        Instant now = Instant.now();
        device = new UavDeviceEntity();
        device.setId(UUID.randomUUID());                        // id UUID PK（应用生成）
        device.setDeviceCode(uavId);                            // device_code NOT NULL
        device.setDeviceName(uavId);                            // device_name NOT NULL
        device.setManufacturer(message.path("source").asText(null)); // manufacturer
        device.setAdapterType("MOCK");                          // adapter_type NOT NULL
        device.setDeviceStatus("CONNECTING");                   // device_status NOT NULL（等待遥测刷新）
        device.setCreatedAt(now);                               // created_at NOT NULL
        device.setUpdatedAt(now);                               // updated_at NOT NULL
        return deviceRepository.save(device);
    }

    private MediaFileEntity saveMediaFile(UavDeviceEntity device, JsonNode message,
                                          JsonNode media, String mediaType, Instant capturedAt,
                                          String scenarioType) {
        String url = media.path("url").asText(null);
        String format = media.path("format").asText(null);
        Instant now = Instant.now();

        MediaFileEntity mf = new MediaFileEntity();
        mf.setId(UUID.randomUUID());                            // id UUID PK（应用生成）
        mf.setUavId(device.getId());                            // uav_id
        mf.setMediaType(mediaType.trim().toUpperCase());        // media_type NOT NULL
        mf.setMimeType(mimeForFormat(format));                  // mime_type
        mf.setStorageProvider(storageProviderFor(url));         // storage_provider
        mf.setBucketName(bucketFor(url));                       // bucket_name
        mf.setObjectKey(objectKeyFor(url, protocolMediaIdOrNull(media))); // object_key NOT NULL
        mf.setFileSize(longOrNull(media, "size"));              // file_size
        mf.setCapturedAt(capturedAt);                           // captured_at
        Double lat = doubleOrNull(media.path("position"), "latitude");
        Double lon = doubleOrNull(media.path("position"), "longitude");
        mf.setLatitude(lat);                                    // latitude
        mf.setLongitude(lon);                                   // longitude
        mf.setGeometry(GeoUtils.toPoint(lat, lon));             // geometry
        mf.setWidth(intOrNull(media, "width"));                 // width
        mf.setHeight(intOrNull(media, "height"));               // height
        mf.setMetadata(buildMetadata(message, media, url, scenarioType)); // metadata JSONB
        mf.setCreatedAt(now);                                   // created_at NOT NULL
        return mediaFileRepository.save(mf);
    }

    /** metadata：消息头 + 拍摄参数 + 协议 mediaId/url + 场景提示（D2）宽松留存 */
    private Map<String, Object> buildMetadata(JsonNode message, JsonNode media, String url,
                                              String scenarioType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "protocolMediaId", media.path("mediaId").asText(null));
        metadata.put("scenarioType", scenarioType);
        putIfNotNull(metadata, "messageId", message.path("messageId").asText(null));
        putIfNotNull(metadata, "messageType", message.path("messageType").asText(null));
        putIfNotNull(metadata, "source", message.path("source").asText(null));
        if (message.path("sequence").isInt()) {
            metadata.put("sequence", message.path("sequence").asInt());
        }
        putIfNotNull(metadata, "messageTimestamp", message.path("timestamp").asText(null));
        putIfNotNull(metadata, "url", url);
        putIfNotNull(metadata, "format", media.path("format").asText(null));
        copyObject(metadata, "camera", media.path("camera"));
        copyObject(metadata, "gimbal", media.path("gimbal"));
        copyObject(metadata, "thermal", media.path("thermal"));
        return metadata;
    }

    private static void copyObject(Map<String, Object> target, String key, JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    copy.put(e.getKey(), e.getValue().isValueNode()
                            ? e.getValue().asText() : e.getValue().toString());
                }
            });
            target.put(key, copy);
        }
    }

    private static void putIfNotNull(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String protocolMediaIdOrNull(JsonNode media) {
        String v = media.path("mediaId").asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    /** s3://bucket/key → "S3"；minio:// → "MINIO"；http(s):// → "HTTP"；其它/缺失 → null */
    private static String storageProviderFor(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String lower = url.toLowerCase();
        if (lower.startsWith("s3://")) {
            return "S3";
        }
        if (lower.startsWith("minio://")) {
            return "MINIO";
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return "HTTP";
        }
        return "UNKNOWN";
    }

    /** s3://bucket/key 解析 bucket（无分隔符时整体为 bucket 名） */
    private static String bucketFor(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase();
        int scheme = lower.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) : (rest.isBlank() ? null : rest);
    }

    /** object_key NOT NULL：s3://bucket/key → key；其它 url 原样；缺失兜底 "media/{protocolMediaId}"，
     *  再兜底 "media/" + 随机 UUID */
    private static String objectKeyFor(String url, String protocolMediaId) {
        if (url != null && !url.isBlank()) {
            int scheme = url.indexOf("://");
            String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
            int slash = rest.indexOf('/');
            if (slash >= 0 && slash < rest.length() - 1) {
                return rest.substring(slash + 1);
            }
            return rest;
        }
        if (protocolMediaId != null) {
            return "media/" + protocolMediaId;
        }
        return "media/" + UUID.randomUUID();
    }

    /** media.format → MIME（JPEG/JPG/PNG/TIFF 常用映射，其余小写原名） */
    private static String mimeForFormat(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        return switch (format.trim().toUpperCase()) {
            case "JPEG", "JPG" -> "image/jpeg";
            case "PNG" -> "image/png";
            case "TIFF" -> "image/tiff";
            case "MP4" -> "video/mp4";
            default -> format.trim().toLowerCase();
        };
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.intValue();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asLong();
    }

    private static Instant parseInstant(String text, Instant fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}

package com.forestfire.uav.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.forestfire.uav.device.FlightStatusMapper;
import com.forestfire.uav.device.UavDeviceEntity;
import com.forestfire.uav.device.UavDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 遥测接入服务：POST /api/v1/uavs/{uavId}/telemetry-ingest。
 *
 * <p>入参为整条 UAV_STATE 消息 JSON（docs/protocol/uav-json-schema/examples/uav-state.json），
 * 用 JsonNode 宽松解析所需字段。</p>
 *
 * <p>逻辑：
 * 1) 按 device_code=uavId upsert uav_device（不存在则创建）；
 * 2) 插入一条 uav_telemetry（列映射见 {@link UavTelemetryEntity} 类注释核对记录）。</p>
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private final UavDeviceRepository deviceRepository;
    private final UavTelemetryRepository telemetryRepository;

    public TelemetryIngestService(UavDeviceRepository deviceRepository,
                                  UavTelemetryRepository telemetryRepository) {
        this.deviceRepository = deviceRepository;
        this.telemetryRepository = telemetryRepository;
    }

    /** ingest 结果视图 */
    public record IngestResult(
            String uavId,
            UUID deviceId,
            String deviceStatus,
            boolean deviceCreated,
            Long telemetryId,
            Instant eventTime
    ) {
    }

    @Transactional
    public IngestResult ingest(String uavId, JsonNode message) {
        // ---- 宽松解析 UAV_STATE 消息所需字段 ----
        Instant eventTime = parseInstant(message.path("timestamp").asText(null), Instant.now());
        JsonNode deviceNode = message.path("device");
        JsonNode position = message.path("position");
        JsonNode attitude = message.path("attitude");
        JsonNode velocity = message.path("velocity");
        JsonNode navigation = message.path("navigation");
        JsonNode battery = message.path("battery");
        JsonNode gimbal = message.path("gimbal");
        JsonNode flight = message.path("flight");
        JsonNode home = message.path("home");
        JsonNode link = message.path("link");

        String deviceStatus = FlightStatusMapper.toDeviceStatus(
                textOrNull(flight, "status"));
        Instant now = Instant.now();

        // ---- 1. upsert uav_device（device_code = uavId）----
        UavDeviceEntity device = deviceRepository.findByDeviceCode(uavId).orElse(null);
        boolean created = false;
        if (device == null) {
            created = true;
            device = new UavDeviceEntity();
            device.setId(UUID.randomUUID());                    // id UUID PK（应用生成）
            device.setDeviceCode(uavId);                        // device_code NOT NULL
            device.setDeviceName(uavId);                        // device_name NOT NULL
            device.setManufacturer(textOrNull(message, "source")); // manufacturer = message.source
            device.setAdapterType("MOCK");                      // adapter_type NOT NULL
            device.setCreatedAt(now);                           // created_at NOT NULL
        }
        // 设备信息随消息更新（存在则刷新）
        device.setDeviceStatus(deviceStatus);                   // device_status NOT NULL
        if (device.getManufacturer() == null) {
            device.setManufacturer(textOrNull(message, "source"));
        }
        device.setModel(textOrNull(deviceNode, "model"));
        device.setSerialNumber(textOrNull(deviceNode, "serialNumber"));
        device.setFirmwareVersion(textOrNull(deviceNode, "firmwareVersion"));
        // home 坐标（消息携带时更新）
        if (home.hasNonNull("latitude")) {
            device.setHomeLatitude(home.path("latitude").asDouble());
        }
        if (home.hasNonNull("longitude")) {
            device.setHomeLongitude(home.path("longitude").asDouble());
        }
        // 当前位置 / 电量快照
        device.setCurrentLatitude(doubleOrNull(position, "latitude"));
        device.setCurrentLongitude(doubleOrNull(position, "longitude"));
        device.setCurrentHeight(doubleOrNull(position, "absoluteAltitude"));
        device.setBatteryPercent(decimalOrNull(battery, "percentage"));
        device.setRemainingFlightTime(intOrNull(battery, "remainingFlightTime"));
        device.setLastOnlineAt(now);
        device.setLastTelemetryAt(eventTime);
        device.setUpdatedAt(now);                               // updated_at NOT NULL
        device = deviceRepository.save(device);

        // ---- 2. 插入 uav_telemetry（逐列映射，见实体类注释核对记录）----
        UavTelemetryEntity t = new UavTelemetryEntity();
        // id：BIGSERIAL 由 DB 序列生成（IDENTITY），不手动赋值
        t.setUavId(device.getId());                             // NOT NULL
        t.setEventTime(eventTime);                              // NOT NULL
        t.setLatitude(doubleOrNull(position, "latitude"));
        t.setLongitude(doubleOrNull(position, "longitude"));
        t.setHeight(doubleOrNull(position, "absoluteAltitude"));
        t.setRelativeHeight(doubleOrNull(position, "relativeAltitude"));
        t.setHorizontalSpeed(doubleOrNull(velocity, "horizontalSpeed"));
        t.setVerticalSpeed(doubleOrNull(velocity, "verticalSpeed"));
        t.setHeading(doubleOrNull(attitude, "heading"));
        t.setPitch(doubleOrNull(attitude, "pitch"));
        t.setRoll(doubleOrNull(attitude, "roll"));
        t.setGpsSatellites(intOrNull(navigation, "gpsSatellites"));
        t.setRtkSatellites(intOrNull(navigation, "rtkSatellites"));
        t.setRtkStatus(textOrNull(navigation, "rtkStatus"));
        t.setBatteryPercent(decimalOrNull(battery, "percentage"));
        t.setRemainingFlightTime(intOrNull(battery, "remainingFlightTime"));
        t.setGimbalPitch(doubleOrNull(gimbal, "pitch"));
        t.setGimbalRoll(doubleOrNull(gimbal, "roll"));
        t.setGimbalYaw(doubleOrNull(gimbal, "yaw"));
        t.setFlightMode(textOrNull(flight, "mode"));
        t.setMissionId(parseUuidOrNull(textOrNull(flight, "missionId")));
        // communication_status ← link.connected 派生（缺失按断链处理，偏保守）
        boolean linkConnected = link.path("connected").asBoolean(false);
        t.setCommunicationStatus(linkConnected ? "CONNECTED" : "DISCONNECTED");
        t.setCreatedAt(now);                                    // NOT NULL
        t = telemetryRepository.save(t);

        log.debug("ingested UAV_STATE for {}: deviceCreated={}, telemetryId={}",
                uavId, created, t.getId());

        return new IngestResult(uavId, device.getId(), deviceStatus, created, t.getId(), eventTime);
    }

    // ---------------- 宽松取值工具 ----------------

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.intValue();
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.decimalValue();
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

    /** 宽容 UUID 解析：如 "MISSION-001" 非法时返回 null（mission_id 可空） */
    private static UUID parseUuidOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

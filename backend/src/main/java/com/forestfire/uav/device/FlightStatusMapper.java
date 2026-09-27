package com.forestfire.uav.device;

import java.util.Map;

/**
 * FlightStatus（协议层 flight.status，enums.yaml UAVStatus 12 值）
 * → DeviceStatus（设备级 uav_device.device_status，enums.yaml DeviceStatus 10 值）映射。
 *
 * <p>映射基线（任务书给定，与 enums.yaml 两枚举值集对齐）：</p>
 * <pre>
 * INIT              → CONNECTING
 * IDLE              → IDLE
 * ARMED             → IDLE
 * TAKEOFF           → TAKEOFF
 * FLYING            → AIRBORNE
 * MISSION_EXECUTING → MISSION_EXECUTING
 * HOVERING          → AIRBORNE
 * PAUSED            → AIRBORNE
 * RETURNING         → RETURNING
 * LANDING           → LANDING
 * LANDED            → IDLE
 * EMERGENCY         → ERROR
 * </pre>
 */
public final class FlightStatusMapper {

    private FlightStatusMapper() {
    }

    private static final Map<String, String> MAPPING = Map.ofEntries(
            Map.entry("INIT", "CONNECTING"),
            Map.entry("IDLE", "IDLE"),
            Map.entry("ARMED", "IDLE"),
            Map.entry("TAKEOFF", "TAKEOFF"),
            Map.entry("FLYING", "AIRBORNE"),
            Map.entry("MISSION_EXECUTING", "MISSION_EXECUTING"),
            Map.entry("HOVERING", "AIRBORNE"),
            Map.entry("PAUSED", "AIRBORNE"),
            Map.entry("RETURNING", "RETURNING"),
            Map.entry("LANDING", "LANDING"),
            Map.entry("LANDED", "IDLE"),
            Map.entry("EMERGENCY", "ERROR")
    );

    /**
     * 未知/缺失的 flight.status 兜底为 CONNECTING（与 INIT 语义一致，表示设备尚未进入稳定态）。
     */
    public static String toDeviceStatus(String flightStatus) {
        if (flightStatus == null || flightStatus.isBlank()) {
            return "CONNECTING";
        }
        return MAPPING.getOrDefault(flightStatus.trim().toUpperCase(), "CONNECTING");
    }
}

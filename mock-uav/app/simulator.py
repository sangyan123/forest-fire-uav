"""Forest-UAV-T1 simulator.

Publishes a complete UAV_STATE and a UAV_TELEMETRY to MQTT every tick (1 Hz),
executes UAV_COMMANDs received on ``uav/{deviceId}/command`` and answers them with
UAV_COMMAND_RESULT messages on ``uav/{deviceId}/command/result``.

Message structures are identical to:
  docs/protocol/uav-json-schema/examples/uav-state.json
  docs/protocol/uav-json-schema/examples/uav-command-result.json
"""

import asyncio
import json
import logging
import math
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

from .config import DEVICE_ID, MQTT_HOST, MQTT_PORT

log = logging.getLogger("mock-uav.simulator")

MODEL = "Forest-UAV-T1"

HOME_LAT = 30.1200
HOME_LON = 114.1200
HOME_ALTITUDE = 260.0

CRUISE_SPEED_MPS = 8.0
TICK_SECONDS = 1.0
ARRIVAL_RADIUS_M = CRUISE_SPEED_MPS * TICK_SECONDS  # < 8 m -> arrived
BATTERY_DRAIN_PER_TICK = 1.0 / 60.0  # 1% per minute
BATTERY_FLOOR = 5.0

M_PER_DEG_LAT = 111_320.0

# Rectangular wayline around Home: 200 m x 100 m -> perimeter ~600 m.
# Offsets are (east_m, north_m) from the home point.
WAYLINE_CORNER_OFFSETS_M = [
    (100.0, 50.0),
    (-100.0, 50.0),
    (-100.0, -50.0),
    (100.0, -50.0),
]


def _utc_now_iso() -> str:
    """Current UTC time as ISO 8601 with millisecond precision and Z suffix."""
    now = datetime.now(timezone.utc)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}Z"


def _m_per_deg_lon(lat: float) -> float:
    return M_PER_DEG_LAT * math.cos(math.radians(lat))


def _distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    d_north = (lat2 - lat1) * M_PER_DEG_LAT
    d_east = (lon2 - lon1) * _m_per_deg_lon((lat1 + lat2) / 2.0)
    return math.hypot(d_north, d_east)


def _bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    d_north = (lat2 - lat1) * M_PER_DEG_LAT
    d_east = (lon2 - lon1) * _m_per_deg_lon((lat1 + lat2) / 2.0)
    if d_north == 0.0 and d_east == 0.0:
        return 0.0
    return math.degrees(math.atan2(d_east, d_north)) % 360.0


class Simulator:
    """Single-device flight simulator: state machine + MQTT publisher + command executor."""

    def __init__(self) -> None:
        self._client = None
        self._loop = None
        self._task = None
        self._running = False

        self._sequence = 0

        # kinematics
        self._latitude = HOME_LAT
        self._longitude = HOME_LON
        self._heading = 0.0
        self._moving = False

        # battery: 100%, -1/60 per tick, floor 5
        self._battery = 100.0

        # flight state
        self._flight_status = "FLYING"  # FlightStatus
        self._flight_mode = "WAYLINE"  # FlightMode
        self._armed = True
        self._mission_id = "MISSION-001"

        # wayline corners (lat, lon)
        self._waypoints = [
            (HOME_LAT + north / M_PER_DEG_LAT, HOME_LON + east / _m_per_deg_lon(HOME_LAT))
            for east, north in WAYLINE_CORNER_OFFSETS_M
        ]
        self._waypoint_index = 0

        # goto / return-home target
        self._target = None
        self._goto_started_at = None
        self._pending_command_id = None

    # ------------------------------------------------------------------
    # lifecycle
    # ------------------------------------------------------------------

    async def start(self) -> None:
        """Idempotently start the MQTT link and the 1 Hz simulation loop."""
        if self._running and self._task is not None and not self._task.done():
            return
        self._loop = asyncio.get_running_loop()
        if self._client is None:
            self._client = mqtt.Client(
                callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                client_id=f"mock-uav-{uuid.uuid4().hex[:8]}",
                clean_session=True,
            )
            self._client.on_connect = self._on_connect
            self._client.on_disconnect = self._on_disconnect
            self._client.on_message = self._on_message
            # connect_async + loop_start: keeps retrying while the broker is not up yet
            self._client.connect_async(MQTT_HOST, MQTT_PORT, keepalive=60)
            self._client.loop_start()
        self._running = True
        self._task = self._loop.create_task(self._run_loop())
        log.info("Simulator started (deviceId=%s, mqtt=%s:%s)", DEVICE_ID, MQTT_HOST, MQTT_PORT)

    async def stop(self) -> None:
        """Stop the simulation loop (the MQTT connection stays for a later restart)."""
        self._running = False
        if self._task is not None and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        self._task = None
        log.info("Simulator stopped")

    async def _run_loop(self) -> None:
        log.info("Simulation loop running at %.1f Hz (speed %.1f m/s)", 1.0 / TICK_SECONDS, CRUISE_SPEED_MPS)
        while self._running:
            started = time.monotonic()
            try:
                self._tick()
            except Exception:  # noqa: BLE001 - the loop must survive any tick error
                log.exception("Simulation tick failed")
            await asyncio.sleep(max(0.0, TICK_SECONDS - (time.monotonic() - started)))

    # ------------------------------------------------------------------
    # paho callbacks (broker thread) -> marshalled onto the asyncio loop
    # ------------------------------------------------------------------

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if getattr(reason_code, "is_failure", False):
            log.error("MQTT connect failed: %s", reason_code)
            return
        client.subscribe(f"uav/{DEVICE_ID}/command", qos=1)
        log.info("Connected to MQTT broker; subscribed uav/%s/command (QoS 1)", DEVICE_ID)

    def _on_disconnect(self, client, userdata, flags, reason_code, properties):
        log.warning("MQTT disconnected: %s (paho will auto-reconnect)", reason_code)

    def _on_message(self, client, userdata, msg):
        payload = bytes(msg.payload)
        if self._loop is not None:
            self._loop.call_soon_threadsafe(self._handle_command, payload)

    # ------------------------------------------------------------------
    # command handling (runs on the asyncio loop thread)
    # ------------------------------------------------------------------

    def _handle_command(self, raw: bytes) -> None:
        try:
            message = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            log.warning("Ignored malformed command payload")
            return
        if not isinstance(message, dict):
            return
        target_device = message.get("deviceId")
        if target_device is not None and target_device != DEVICE_ID:
            return

        command_id = str(message.get("commandId") or "")
        command_type = str(message.get("commandType") or "").upper()
        payload = message.get("payload")
        if not isinstance(payload, dict):
            payload = {}
        log.info("UAV_COMMAND received: type=%s commandId=%s", command_type, command_id)

        # every command is acknowledged as EXECUTING first
        self._publish_result(command_id, "EXECUTING", {})

        if command_type == "GOTO":
            self._target = {
                "latitude": float(payload.get("latitude", self._latitude)),
                "longitude": float(payload.get("longitude", self._longitude)),
                "altitude": payload.get("altitude"),
                "altitudeMode": payload.get("altitudeMode"),
            }
            self._flight_mode = "GOTO"
            self._flight_status = "FLYING"
            self._armed = True
            self._goto_started_at = time.monotonic()
            self._pending_command_id = command_id  # SUCCESS is published on arrival
        elif command_type == "RETURN_HOME":
            self._target = {
                "latitude": HOME_LAT,
                "longitude": HOME_LON,
                "altitude": None,
                "altitudeMode": None,
            }
            self._flight_mode = "RETURN_HOME"
            self._flight_status = "RETURNING"
            self._armed = True
            self._goto_started_at = None
            self._pending_command_id = None
            self._publish_result(command_id, "SUCCESS", {})
        elif command_type == "TAKEOFF":
            self._armed = True
            self._flight_status = "FLYING"
            if self._flight_mode not in ("GOTO", "RETURN_HOME", "WAYLINE"):
                self._flight_mode = "HOVER"
            self._publish_result(command_id, "SUCCESS", {})
        elif command_type == "LAND":
            self._flight_status = "LANDED"
            self._flight_mode = "AUTO_LAND"
            self._target = None
            self._pending_command_id = None
            self._publish_result(command_id, "SUCCESS", {})
        elif command_type == "PAUSE":
            self._flight_status = "PAUSED"
            self._publish_result(command_id, "SUCCESS", {})
        elif command_type == "RESUME":
            self._flight_status = "FLYING"
            if self._flight_mode not in ("GOTO", "RETURN_HOME"):
                self._flight_mode = "WAYLINE"
            self._publish_result(command_id, "SUCCESS", {})
        elif command_type in ("CAPTURE_RGB", "CAPTURE_THERMAL"):
            self._publish_result(command_id, "SUCCESS", {})
        else:
            log.warning("Unknown commandType %s (commandId=%s)", command_type, command_id)
            self._publish_result(command_id, "FAILED", {"message": f"unknown commandType: {command_type}"})

    def _publish_result(self, command_id: str, status: str, result: dict) -> None:
        self._sequence += 1
        message = {
            "schemaVersion": "1.0",
            "messageId": f"CMD-RESULT-{uuid.uuid4().hex[:8]}",
            "messageType": "UAV_COMMAND_RESULT",
            "deviceId": DEVICE_ID,
            "timestamp": _utc_now_iso(),
            "source": "MOCK",
            "sequence": self._sequence,
            "commandId": command_id,
            "status": status,
            "result": result,
        }
        self._publish(f"uav/{DEVICE_ID}/command/result", message)
        log.info("UAV_COMMAND_RESULT: commandId=%s status=%s result=%s", command_id, status, result)

    # ------------------------------------------------------------------
    # simulation tick
    # ------------------------------------------------------------------

    def _tick(self) -> None:
        self._moving = False
        if self._flight_status not in ("PAUSED", "LANDED"):
            if self._flight_mode in ("GOTO", "RETURN_HOME") and self._target is not None:
                arrived = not self._step_toward(self._target["latitude"], self._target["longitude"])
                self._moving = not arrived
                if arrived:
                    self._flight_mode = "HOVER"
                    self._flight_status = "HOVERING"
                    if self._pending_command_id is not None:
                        elapsed_ms = int((time.monotonic() - self._goto_started_at) * 1000)
                        self._publish_result(self._pending_command_id, "SUCCESS", {"executionTimeMs": elapsed_ms})
                        self._pending_command_id = None
                    self._goto_started_at = None
            elif self._flight_mode == "WAYLINE":
                waypoint = self._waypoints[self._waypoint_index]
                arrived = not self._step_toward(waypoint[0], waypoint[1])
                self._moving = not arrived
                if arrived:
                    self._waypoint_index = (self._waypoint_index + 1) % len(self._waypoints)

        self._battery = max(BATTERY_FLOOR, self._battery - BATTERY_DRAIN_PER_TICK)

        self._publish_state()
        self._publish_telemetry()

    def _step_toward(self, target_lat: float, target_lon: float) -> bool:
        """Move one tick (8 m) toward the target. Returns True while still en route."""
        distance = _distance_m(self._latitude, self._longitude, target_lat, target_lon)
        if distance <= ARRIVAL_RADIUS_M:
            self._latitude = target_lat
            self._longitude = target_lon
            return False
        self._heading = _bearing_deg(self._latitude, self._longitude, target_lat, target_lon)
        rad = math.radians(self._heading)
        step_north = CRUISE_SPEED_MPS * TICK_SECONDS * math.cos(rad)
        step_east = CRUISE_SPEED_MPS * TICK_SECONDS * math.sin(rad)
        self._latitude += step_north / M_PER_DEG_LAT
        self._longitude += step_east / _m_per_deg_lon(self._latitude)
        return True

    # ------------------------------------------------------------------
    # outgoing messages
    # ------------------------------------------------------------------

    def _submodels(self) -> dict:
        """Submodels shared by UAV_STATE and UAV_TELEMETRY."""
        speed = CRUISE_SPEED_MPS if self._moving else 0.0
        rad = math.radians(self._heading)
        return {
            "position": {
                "latitude": round(self._latitude, 6),
                "longitude": round(self._longitude, 6),
                "absoluteAltitude": 325.42,
                "relativeAltitude": 100.0,
                "groundElevation": 225.42,
                "horizontalAccuracy": 0.8,
                "verticalAccuracy": 1.2,
            },
            "attitude": {
                "heading": round(self._heading, 1),
                "pitch": 2.1,
                "roll": -1.2,
            },
            "velocity": {
                "horizontalSpeed": speed,
                "verticalSpeed": 0.0,
                "northSpeed": round(speed * math.cos(rad), 2),
                "eastSpeed": round(speed * math.sin(rad), 2),
            },
            "battery": {
                "percentage": int(min(100, max(BATTERY_FLOOR, round(self._battery)))),
                "voltage": 23500,
                "current": 4200,
                "temperature": 38.2,
                "remainingFlightTime": int(max(0.0, (self._battery - BATTERY_FLOOR) * 60.0)),
                "returnHomeThreshold": 20,
                "landingThreshold": 10,
            },
            "navigation": {
                "gpsSatellites": 22,
                "rtkSatellites": 17,
                "gpsStatus": "3D_FIX",
                "rtkStatus": "FIXED",
                "positionSource": "RTK",
            },
            "link": {
                "connected": True,
                "latencyMs": 42,
                "signalStrength": 86,
                "networkType": "4G",
            },
        }

    def _publish_state(self) -> None:
        self._sequence += 1
        sub = self._submodels()
        message = {
            "schemaVersion": "1.0",
            "messageId": f"MSG-{uuid.uuid4().hex}",
            "messageType": "UAV_STATE",
            "deviceId": DEVICE_ID,
            "timestamp": _utc_now_iso(),
            "source": "MOCK",
            "sequence": self._sequence,
            "device": {
                "manufacturer": "MOCK",
                "model": MODEL,
                "serialNumber": "MOCK-001",
                "firmwareVersion": "1.0.0",
                "deviceType": "AIRCRAFT",
            },
            "position": sub["position"],
            "attitude": sub["attitude"],
            "velocity": sub["velocity"],
            "navigation": sub["navigation"],
            "battery": sub["battery"],
            "gimbal": {
                "pitch": -35.5,
                "roll": 0.2,
                "yaw": 22.4,
                "mode": "FREE",
            },
            "payload": {
                "rgb": {"available": True, "status": "READY"},
                "thermal": {"available": True, "status": "READY"},
                "zoom": {"available": True, "maxZoom": 20},
            },
            "flight": {
                "status": self._flight_status,
                "mode": self._flight_mode,
                "missionId": self._mission_id,
                "armed": self._armed,
            },
            "home": {
                "latitude": HOME_LAT,
                "longitude": HOME_LON,
                "altitude": HOME_ALTITUDE,
                "distance": round(_distance_m(self._latitude, self._longitude, HOME_LAT, HOME_LON), 1),
            },
            "link": sub["link"],
            "environment": {
                "temperature": 31.5,
                "humidity": 32,
                "windSpeed": 4.8,
                "windDirection": 62,
                "source": "WEATHER_STATION",
            },
            "capability": {
                "rgb": True,
                "thermal": True,
                "zoom": True,
                "gimbalLookAt": True,
                "wayline": True,
                "liveStream": True,
                "photo": True,
                "video": True,
                "rtk": True,
                "returnHome": True,
            },
        }
        self._publish(f"uav/{DEVICE_ID}/state", message)

    def _publish_telemetry(self) -> None:
        self._sequence += 1
        sub = self._submodels()
        message = {
            "schemaVersion": "1.0",
            "messageId": f"MSG-{uuid.uuid4().hex}",
            "messageType": "UAV_TELEMETRY",
            "deviceId": DEVICE_ID,
            "timestamp": _utc_now_iso(),
            "source": "MOCK",
            "sequence": self._sequence,
            "position": sub["position"],
            "attitude": sub["attitude"],
            "velocity": sub["velocity"],
            "battery": sub["battery"],
            "navigation": sub["navigation"],
            "link": sub["link"],
        }
        self._publish(f"uav/{DEVICE_ID}/telemetry", message)

    def _publish(self, topic: str, message: dict) -> None:
        if self._client is None:
            log.warning("MQTT client not ready; dropped message on %s", topic)
            return
        info = self._client.publish(topic, json.dumps(message, ensure_ascii=False), qos=1)
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            log.warning("Publish to %s returned rc=%s (queued for delivery on reconnect)", topic, info.rc)

    # ------------------------------------------------------------------
    # REST support
    # ------------------------------------------------------------------

    def status_snapshot(self) -> dict:
        return {
            "deviceId": DEVICE_ID,
            "model": MODEL,
            "running": self._running,
            "mqttConnected": bool(self._client is not None and self._client.is_connected()),
            "position": {
                "latitude": round(self._latitude, 6),
                "longitude": round(self._longitude, 6),
            },
            "batteryPercentage": round(self._battery, 2),
            "flight": {
                "status": self._flight_status,
                "mode": self._flight_mode,
                "armed": self._armed,
            },
            "target": self._target,
            "sequence": self._sequence,
        }

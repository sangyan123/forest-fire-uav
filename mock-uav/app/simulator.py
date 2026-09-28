"""Forest-UAV-T1 simulator.

Publishes a complete UAV_STATE and a UAV_TELEMETRY to MQTT every tick (1 Hz),
executes UAV_COMMANDs received on ``uav/{deviceId}/command`` and answers them with
UAV_COMMAND_RESULT messages on ``uav/{deviceId}/command/result``.

Message structures are identical to:
  docs/protocol/uav-json-schema/examples/uav-state.json
  docs/protocol/uav-json-schema/examples/uav-command-result.json
  docs/protocol/uav-json-schema/examples/uav-media.json
  docs/protocol/uav-json-schema/examples/uav-event.json

Fire scenario (customer demo): POST /simulator/scenarios/fire/start flies the UAV to the
fire point at 15 m/s and, on arrival (< 10 m), automatically starts "fire capture" — one
UAV_MEDIA on ``uav/{deviceId}/media`` (QoS 1) every 2 s (4 RGB images, then 1 thermal image,
repeating) until POST /simulator/scenarios/fire/stop restores the rectangular wayline patrol.

Demo verdict lines (body field ``verdict``, default "CONFIRMED"):
  - CONFIRMED   -> every media published while the scenario is active carries
                   ``media.metadata = {"scenarioType": "FIRE"}``
  - FALSE_ALARM -> same flight/capture behaviour, but media carry
                   ``media.metadata = {"scenarioType": "FALSE_ALARM"}``
metadata is a demo-mode scenario hint (extra field allowed by uav-media.schema.json);
real evidence replaces it once a real detection model is online.

Scenario registry (baseline ch.61 DEMO scenarios, POST /simulator/scenarios/{scenarioId}/start):
  - scenario-01 正常巡检  -> stop the fire scenario / restore wayline patrol (idempotent)
  - scenario-02 火情发现  -> fire scenario, verdict CONFIRMED
  - scenario-04 误报      -> fire scenario, verdict FALSE_ALARM
status.currentScenarioId reports the active registry id ("scenario-01" on normal patrol);
the legacy /simulator/scenarios/fire/start|stop endpoints set it from their verdict too.
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

# Fire scenario: default fire point ~800 m north-east of Home.
FIRE_DEFAULT_LAT = 30.1235
FIRE_DEFAULT_LON = 114.1285
FIRE_TRANSIT_SPEED_MPS = 15.0  # demo pace; restored to CRUISE_SPEED_MPS on arrival
FIRE_ARRIVAL_RADIUS_M = 10.0  # < 10 m -> arrived, capture starts
MEDIA_CAPTURE_INTERVAL_TICKS = 2  # one UAV_MEDIA every 2 s (1 Hz tick)
RGB_IMAGES_PER_THERMAL = 4  # every 4 RGB images insert 1 THERMAL_IMAGE

# Demo verdict lines: FALSE_ALARM is the "false alarm" demo run. Both lines fly and capture
# identically; only media.metadata.scenarioType (consumed by the backend demo) differs.
SCENARIO_VERDICTS = ("CONFIRMED", "FALSE_ALARM")
VERDICT_SCENARIO_TYPE = {"CONFIRMED": "FIRE", "FALSE_ALARM": "FALSE_ALARM"}

# Baseline ch.61 DEMO scenario registry ids; the legacy fire/start endpoints map to these
# via their verdict so currentScenarioId stays consistent across both API styles.
SCENARIO_ID_PATROL = "scenario-01"
VERDICT_SCENARIO_ID = {"CONFIRMED": "scenario-02", "FALSE_ALARM": "scenario-04"}

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
        self._speed_mps = CRUISE_SPEED_MPS

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

        # fire scenario (customer demo)
        self._fire_scenario = {
            "active": False,
            "latitude": None,
            "longitude": None,
            "capturing": False,
            "verdict": "CONFIRMED",
        }
        self._scenario_id = SCENARIO_ID_PATROL  # baseline ch.61 registry id
        self._capture_tick = 0
        self._rgb_since_thermal = 0
        self._media_count = 0

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

    # ------------------------------------------------------------------
    # fire scenario (customer demo)
    # ------------------------------------------------------------------

    def start_fire_scenario(self, latitude: float | None = None, longitude: float | None = None,
                            verdict: str = "CONFIRMED") -> None:
        """Fly to the fire point (default north-east of Home) at 15 m/s and start fire capture on arrival.

        verdict "CONFIRMED" (default) or "FALSE_ALARM" selects the demo line; both behave
        identically except for media.metadata.scenarioType ("FIRE" vs "FALSE_ALARM").
        """
        verdict = str(verdict).strip().upper() if verdict else "CONFIRMED"
        if verdict not in SCENARIO_VERDICTS:
            raise ValueError(f"verdict must be one of {SCENARIO_VERDICTS}, got: {verdict!r}")
        fire_lat = float(latitude) if latitude is not None else FIRE_DEFAULT_LAT
        fire_lon = float(longitude) if longitude is not None else FIRE_DEFAULT_LON
        self._fire_scenario = {
            "active": True,
            "latitude": fire_lat,
            "longitude": fire_lon,
            "capturing": False,
            "verdict": verdict,
        }
        self._scenario_id = VERDICT_SCENARIO_ID[verdict]
        self._capture_tick = 0
        self._rgb_since_thermal = 0
        self._media_count = 0
        self._speed_mps = FIRE_TRANSIT_SPEED_MPS
        self._target = {
            "latitude": fire_lat,
            "longitude": fire_lon,
            "altitude": None,
            "altitudeMode": None,
        }
        self._flight_mode = "WAYLINE"
        self._flight_status = "FLYING"
        self._armed = True
        self._goto_started_at = None
        self._pending_command_id = None
        log.info("Fire scenario started: fire point (%s, %s), transit at %.1f m/s",
                 fire_lat, fire_lon, FIRE_TRANSIT_SPEED_MPS)
        self._publish_fire_scenario_event(fire_lat, fire_lon)

    def stop_fire_scenario(self) -> None:
        """Stop fire capture, clear the scenario and restore the rectangular wayline patrol (8 m/s)."""
        self._fire_scenario = {
            "active": False,
            "latitude": None,
            "longitude": None,
            "capturing": False,
            # verdict of the last run is kept for observability (status display only)
            "verdict": self._fire_scenario["verdict"],
        }
        self._scenario_id = SCENARIO_ID_PATROL  # back to normal patrol
        self._speed_mps = CRUISE_SPEED_MPS
        self._target = None
        self._goto_started_at = None
        self._pending_command_id = None
        self._flight_mode = "WAYLINE"
        self._flight_status = "FLYING"
        self._waypoint_index = self._nearest_waypoint_index()
        log.info("Fire scenario stopped; restored wayline patrol at %.1f m/s", CRUISE_SPEED_MPS)

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
            media_type = "THERMAL_IMAGE" if command_type == "CAPTURE_THERMAL" else "RGB_IMAGE"
            media_id = self._publish_media(media_type)
            self._publish_result(command_id, "SUCCESS", {"mediaId": media_id})
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
            if self._fire_scenario["active"]:
                self._tick_fire_scenario()
            elif self._flight_mode in ("GOTO", "RETURN_HOME") and self._target is not None:
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

    def _tick_fire_scenario(self) -> None:
        """Transit to the fire point at 15 m/s, then hover and auto-capture media every 2 s."""
        if not self._fire_scenario["capturing"]:
            arrived = not self._step_toward(
                self._fire_scenario["latitude"],
                self._fire_scenario["longitude"],
                speed=FIRE_TRANSIT_SPEED_MPS,
                arrival_radius=FIRE_ARRIVAL_RADIUS_M,
            )
            self._moving = not arrived
            if arrived:
                self._fire_scenario["capturing"] = True
                self._speed_mps = CRUISE_SPEED_MPS  # transit done -> demo cruise speed again
                self._flight_mode = "HOVER"
                self._flight_status = "HOVERING"
                log.info("Fire scenario: arrived at fire point (%.6f, %.6f), media capture started",
                         self._latitude, self._longitude)
        else:
            # hovering over the fire point: one UAV_MEDIA every MEDIA_CAPTURE_INTERVAL_TICKS ticks
            self._capture_tick += 1
            if self._capture_tick % MEDIA_CAPTURE_INTERVAL_TICKS == 0:
                media_type = (
                    "THERMAL_IMAGE"
                    if self._rgb_since_thermal >= RGB_IMAGES_PER_THERMAL
                    else "RGB_IMAGE"
                )
                if media_type == "THERMAL_IMAGE":
                    self._rgb_since_thermal = 0
                else:
                    self._rgb_since_thermal += 1
                self._publish_media(media_type)

    def _step_toward(self, target_lat: float, target_lon: float, speed: float | None = None,
                     arrival_radius: float | None = None) -> bool:
        """Move one tick toward the target. Returns True while still en route."""
        speed = CRUISE_SPEED_MPS if speed is None else speed
        arrival_radius = ARRIVAL_RADIUS_M if arrival_radius is None else arrival_radius
        distance = _distance_m(self._latitude, self._longitude, target_lat, target_lon)
        if distance <= arrival_radius:
            self._latitude = target_lat
            self._longitude = target_lon
            return False
        self._heading = _bearing_deg(self._latitude, self._longitude, target_lat, target_lon)
        rad = math.radians(self._heading)
        step_north = speed * TICK_SECONDS * math.cos(rad)
        step_east = speed * TICK_SECONDS * math.sin(rad)
        self._latitude += step_north / M_PER_DEG_LAT
        self._longitude += step_east / _m_per_deg_lon(self._latitude)
        return True

    # ------------------------------------------------------------------
    # outgoing messages
    # ------------------------------------------------------------------

    def _submodels(self) -> dict:
        """Submodels shared by UAV_STATE and UAV_TELEMETRY."""
        speed = self._speed_mps if self._moving else 0.0
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

    def _publish_media(self, media_type: str) -> str:
        """Publish one UAV_MEDIA on uav/{deviceId}/media; returns the mediaId."""
        self._sequence += 1
        media_id = f"MEDIA-{uuid.uuid4().hex[:8]}"
        now = _utc_now_iso()
        if media_type == "THERMAL_IMAGE":
            url = f"s3://forest-fire/raw/thermal/{uuid.uuid4().hex[:8]}.png"
            media_format = "PNG"
        else:
            url = f"s3://forest-fire/raw/rgb/{uuid.uuid4().hex[:8]}.jpg"
            media_format = "JPEG"
        media = {
            "mediaId": media_id,
            "type": media_type,
            "url": url,
            "width": 3840,
            "height": 2160,
            "format": media_format,
            "size": 3852132,
            "capturedAt": now,
            "position": {
                "latitude": round(self._latitude, 6),
                "longitude": round(self._longitude, 6),
                "altitude": 122.4,
            },
            "camera": {
                "type": "RGB",
                "fovHorizontal": 72,
                "fovVertical": 44,
                "zoom": 5,
            },
            "gimbal": {
                "pitch": -35,
                "yaw": 120,
            },
        }
        if media_type == "THERMAL_IMAGE":
            media["thermal"] = {
                "minTemperature": 28.2,
                "maxTemperature": 87.4,
                "meanTemperature": 34.6,
                "unit": "CELSIUS",
            }
        if self._fire_scenario["active"]:
            # demo-mode scenario hint (extra field, allowed by uav-media.schema.json);
            # the backend demo consumes it to steer CONFIRMED vs FALSE_ALARM verification
            media["metadata"] = {"scenarioType": VERDICT_SCENARIO_TYPE[self._fire_scenario["verdict"]]}
        message = {
            "schemaVersion": "1.0",
            "messageId": f"MEDIA-MSG-{uuid.uuid4().hex[:8]}",
            "messageType": "UAV_MEDIA",
            "deviceId": DEVICE_ID,
            "timestamp": now,
            "source": "MOCK",
            "sequence": self._sequence,
            "media": media,
        }
        self._publish(f"uav/{DEVICE_ID}/media", message)
        self._media_count += 1
        log.info("UAV_MEDIA published: mediaId=%s type=%s url=%s", media_id, media_type, url)
        return media_id

    def _publish_fire_scenario_event(self, fire_lat: float, fire_lon: float) -> None:
        """Publish the MISSION_STARTED UAV_EVENT announcing the fire scenario."""
        self._sequence += 1
        message = {
            "schemaVersion": "1.0",
            "messageId": f"EVENT-MSG-{uuid.uuid4().hex[:8]}",
            "messageType": "UAV_EVENT",
            "deviceId": DEVICE_ID,
            "timestamp": _utc_now_iso(),
            "source": "MOCK",
            "sequence": self._sequence,
            "event": {
                "eventId": f"EVENT-{uuid.uuid4().hex[:8]}",
                "eventType": "MISSION_STARTED",
                "severity": "INFO",
                "data": {
                    "scenarioType": "FIRE",
                    "latitude": fire_lat,
                    "longitude": fire_lon,
                },
            },
        }
        self._publish(f"uav/{DEVICE_ID}/event", message)
        log.info("UAV_EVENT published: MISSION_STARTED (fire scenario at %.6f, %.6f)", fire_lat, fire_lon)

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
            "currentScenarioId": self._scenario_id,
            "fireScenario": {
                "active": self._fire_scenario["active"],
                "latitude": (
                    round(self._fire_scenario["latitude"], 6)
                    if self._fire_scenario["latitude"] is not None else None
                ),
                "longitude": (
                    round(self._fire_scenario["longitude"], 6)
                    if self._fire_scenario["longitude"] is not None else None
                ),
                "capturing": self._fire_scenario["capturing"],
                "verdict": self._fire_scenario["verdict"],
            },
            "sequence": self._sequence,
        }

    def _nearest_waypoint_index(self) -> int:
        """Index of the wayline corner closest to the current position."""
        return min(
            range(len(self._waypoints)),
            key=lambda index: _distance_m(
                self._latitude, self._longitude, self._waypoints[index][0], self._waypoints[index][1]
            ),
        )

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
  - scenario-06 UAV断联   -> COMMUNICATION_LOST -> silence (no MQTT publish at all,
                            default 15 s, body {silenceSeconds} capped at 60 s)
                            -> COMMUNICATION_RECOVERED -> 1 Hz patrol publishing resumes
status.currentScenarioId reports the active registry id ("scenario-01" on normal patrol,
"scenario-06" while the comms silence runs); the legacy /simulator/scenarios/fire/start|stop
endpoints set it from their verdict too.

Fault injection (REST, demo controls):
  - POST /simulator/uavs/{uavId}/battery  body {percent 0..100} -> injects the battery level and
    emits the four-level battery events (<=30 LOW_BATTERY, <=10 CRITICAL_BATTERY)
  - POST /simulator/uavs/{uavId}/failure  body {type} -> emits a severity HIGH event for
    GPS_LOST / RTK_LOST / CAMERA_ERROR / PAYLOAD_ERROR, or a recovery event (RTK_RECOVERED,
    severity INFO) for type "RECOVER"
During the scenario-06 silence every MQTT publish (state/telemetry/event/media, including
REST-injected events) is suppressed; REST keeps answering and /simulator/status reports
commsSilent=true plus resumeInSeconds. A running fire scenario pauses its capture timing
during the silence and continues afterwards.
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

CRUISE_SPEED_MPS = 12.0  # 2026-10-05 demo 调整（原 8.0）：配合巡逻区放大保持移动感；火情转场仍 15 m/s
TICK_SECONDS = 1.0
ARRIVAL_RADIUS_M = CRUISE_SPEED_MPS * TICK_SECONDS  # < 12 m -> arrived
BATTERY_DRAIN_PER_TICK = 1.0 / 60.0  # 1% per minute
BATTERY_FLOOR = 5.0

# Fire scenario: default fire point ~800 m north-east of Home.
FIRE_DEFAULT_LAT = 30.1235
FIRE_DEFAULT_LON = 114.1285
# 误报线专用默认点已被“预设点位轮换”取代（见 FIRE_PRESET_POINTS）：轮换保证连续两次
# 注入的点位相距 >火情去重半径100m，误报检测不会被合并进火情事件（D6彩排实测发现）
# 预设起火点：三者两两相距 800m 以上，均在离线瓦片覆盖区（30.08~30.18N, 114.08~114.18E）内
FIRE_PRESET_POINTS = [
    (30.1235, 114.1285),  # P1 Home 东北 ~800m
    (30.1185, 114.1210),  # P2 Home 西南 ~700m
    (30.1265, 114.1360),  # P3 Home 东北 ~1.7km
]
FIRE_TRANSIT_SPEED_MPS = 15.0  # demo pace; restored to CRUISE_SPEED_MPS on arrival
FIRE_ARRIVAL_RADIUS_M = 10.0  # < 10 m -> arrived, capture starts
MEDIA_CAPTURE_INTERVAL_TICKS = 2  # one UAV_MEDIA every 2 s (1 Hz tick)
RGB_IMAGES_PER_THERMAL = 4  # every 4 RGB images insert 1 THERMAL_IMAGE

# 灭火弹挂载（镜像 docs/00-doc/constants.yaml#extinguishing_ball，2026-10-05 demo 增补；
# 真实投放器参数待 Phase 9A 按基线第32章实验协议重定）：
# capacity 机载弹药数（耗尽后 DROP_EXTINGUISHING_BALL 命令 FAILED）、
# drop_max_radius_m 投放安全半径（与目标点距离超限即 FAILED）。
EXTINGUISHING_BALL_CAPACITY = 3
EXTINGUISHING_BALL_DROP_MAX_RADIUS_M = 150.0

# Demo verdict lines: FALSE_ALARM is the "false alarm" demo run. Both lines fly and capture
# identically; only media.metadata.scenarioType (consumed by the backend demo) differs.
SCENARIO_VERDICTS = ("CONFIRMED", "FALSE_ALARM")
VERDICT_SCENARIO_TYPE = {"CONFIRMED": "FIRE", "FALSE_ALARM": "FALSE_ALARM"}

# Baseline ch.61 DEMO scenario registry ids; the legacy fire/start endpoints map to these
# via their verdict so currentScenarioId stays consistent across both API styles.
SCENARIO_ID_PATROL = "scenario-01"
VERDICT_SCENARIO_ID = {"CONFIRMED": "scenario-02", "FALSE_ALARM": "scenario-04"}

# Scenario-06 (baseline ch.62): UAV 断联演示 — COMMUNICATION_LOST, then a silence window with
# no MQTT publishing at all, then COMMUNICATION_RECOVERED and the 1 Hz patrol resumes.
SCENARIO_ID_COMMS_LOST = "scenario-06"
DEFAULT_SILENCE_SECONDS = 25
MAX_SILENCE_SECONDS = 60
# 断联静默下限必须 > 平台离线阈值(15s)+扫描周期(5s)，否则平台来不及置 OFFLINE 通信就恢复了
MIN_SILENCE_SECONDS = 25

# Four-level battery thresholds (baseline ch.36 / uav-event.schema.json): LOW_BATTERY maps to
# LOW_BATTERY_WARNING (30%), CRITICAL_BATTERY to CRITICAL (10%). Only these two levels have
# UAV_EVENT types; the 25% / 20% levels are advisory only in the MVP.
BATTERY_LOW_THRESHOLD = 30.0
BATTERY_CRITICAL_THRESHOLD = 10.0

# Fault injection types (POST /simulator/uavs/{uavId}/failure): the four failure event types
# (severity HIGH) plus "RECOVER", which emits the recovery event (RTK_RECOVERED, INFO).
FAILURE_TYPES = ("GPS_LOST", "RTK_LOST", "CAMERA_ERROR", "PAYLOAD_ERROR")
FAILURE_RECOVER_TYPE = "RECOVER"

M_PER_DEG_LAT = 111_320.0

# Rectangular wayline around Home: 1200 m x 800 m -> perimeter ~4.0 km.
# （2026-10-05 demo 调整：原 200x100m 在 z15 地图上仅约 26px、小于无人机图标本身，
# 视觉上不像巡逻；放大后四角仍全部处于离线瓦片覆盖区 30.09~30.17N / 114.09~114.17E 内，
# 且三个预设起火点（700m~1.7km）仍需明显转场。见 change-log 同日条目。）
# Offsets are (east_m, north_m) from the home point.
WAYLINE_CORNER_OFFSETS_M = [
    (600.0, 400.0),
    (-600.0, 400.0),
    (-600.0, -400.0),
    (600.0, -400.0),
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
        self._fire_preset_index = 0  # 起火点预设轮换指针（每次场景启动+1，取模复位）
        self._rgb_since_thermal = 0
        self._media_count = 0
        self._balls_remaining = EXTINGUISHING_BALL_CAPACITY  # 灭火弹余量（constants.yaml#extinguishing_ball）

        # scenario-06 comms silence (UAV 断联): while silent, no MQTT message is published
        self._comms_silent = False
        self._comms_silence_started_at = None  # time.monotonic() reference
        self._comms_silence_seconds = 0
        self._scenario_id_before_comms_lost = SCENARIO_ID_PATROL
        self._last_failure_type = None  # last injected failure (RECOVER event context)

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
        """Fly to the fire point at 15 m/s and start fire capture on arrival.

        verdict "CONFIRMED" (default) or "FALSE_ALARM" selects the demo line; both behave
        identically except for media.metadata.scenarioType ("FIRE" vs "FALSE_ALARM").

        Fire point: explicit latitude/longitude wins; with no coordinates the **preset points
        rotate** (FIRE_PRESET_POINTS, one advance per start) so consecutive demos land at
        different spots — a customer asking "why is the fire always there?" gets no reason to.
        Rotation also keeps consecutive starts > 100 m (fire dedup radius) apart, so a
        false-alarm line never merges into an open fire incident.
        """
        verdict = str(verdict).strip().upper() if verdict else "CONFIRMED"
        if verdict not in SCENARIO_VERDICTS:
            raise ValueError(f"verdict must be one of {SCENARIO_VERDICTS}, got: {verdict!r}")
        if latitude is None or longitude is None:
            fire_lat, fire_lon = FIRE_PRESET_POINTS[self._fire_preset_index]
            self._fire_preset_index = (self._fire_preset_index + 1) % len(FIRE_PRESET_POINTS)
        else:
            fire_lat, fire_lon = float(latitude), float(longitude)
        self._fire_scenario = {
            "active": True,
            "latitude": fire_lat,
            "longitude": fire_lon,
            "capturing": False,
            "verdict": verdict,
        }
        # 新场景接管飞行：结算挂起的 GOTO 命令（否则永远卡在 EXECUTING，D6 彩排实测）
        if self._pending_command_id is not None:
            self._publish_result(self._pending_command_id, "CANCELLED",
                                 {"reason": "SUPERSEDED_BY_FIRE_SCENARIO"})
            self._pending_command_id = None
        self._target = None
        self._goto_started_at = None
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
        """Stop fire capture, clear the scenario and restore the rectangular wayline patrol (12 m/s)."""
        self._fire_scenario = {
            "active": False,
            "latitude": None,
            "longitude": None,
            "capturing": False,
            # verdict of the last run is kept for observability (status display only)
            "verdict": self._fire_scenario["verdict"],
        }
        self._scenario_id = SCENARIO_ID_PATROL  # back to normal patrol
        if self._comms_silent:
            # scenario-06 silence running: patrol is also the scenario to resume into
            self._scenario_id_before_comms_lost = SCENARIO_ID_PATROL
        self._speed_mps = CRUISE_SPEED_MPS
        self._target = None
        self._goto_started_at = None
        self._pending_command_id = None
        self._flight_mode = "WAYLINE"
        self._flight_status = "FLYING"
        self._waypoint_index = self._nearest_waypoint_index()
        log.info("Fire scenario stopped; restored wayline patrol at %.1f m/s", CRUISE_SPEED_MPS)

    # ------------------------------------------------------------------
    # scenario-06: communication loss (customer demo)
    # ------------------------------------------------------------------

    def start_comms_lost_scenario(self, silence_seconds: int | None = None) -> None:
        """Scenario-06 UAV 断联: COMMUNICATION_LOST -> silence -> COMMUNICATION_RECOVERED.

        Publishes the COMMUNICATION_LOST UAV_EVENT (severity HIGH, data carries the last known
        coordinates), then stops every MQTT publish (state/telemetry/event/media) for
        ``silence_seconds`` (default DEFAULT_SILENCE_SECONDS=25, clamped to
        [MIN_SILENCE_SECONDS, MAX_SILENCE_SECONDS]=[25, 60]). When the silence elapses the next tick publishes
        COMMUNICATION_RECOVERED (severity INFO) first and then resumes the 1 Hz patrol.

        Idempotent: calling again while already silent only resets the silence timer (no
        duplicate COMMUNICATION_LOST). A running fire scenario keeps flying but its capture
        timing pauses during the silence and continues afterwards.
        """
        seconds = DEFAULT_SILENCE_SECONDS if silence_seconds is None else int(silence_seconds)
        seconds = max(MIN_SILENCE_SECONDS, min(MAX_SILENCE_SECONDS, seconds))
        if self._comms_silent:
            # idempotent re-entry: keep the saved pre-silence scenario, just reset the timer
            self._comms_silence_seconds = seconds
            self._comms_silence_started_at = time.monotonic()
            log.info("Comms silence already active; timer reset to %ds", seconds)
            return
        self._scenario_id_before_comms_lost = self._scenario_id
        self._publish_event(
            "COMMUNICATION_LOST",
            "HIGH",
            {
                "reason": "SCENARIO_06_COMMS_LOST",
                "latitude": round(self._latitude, 6),
                "longitude": round(self._longitude, 6),
                "silenceSeconds": seconds,
            },
        )
        self._comms_silent = True
        self._comms_silence_seconds = seconds
        self._comms_silence_started_at = time.monotonic()
        self._scenario_id = SCENARIO_ID_COMMS_LOST
        # 断联期间指令无法送达无人机：挂起的 GOTO 结算为 CANCELLED（避免僵尸 EXECUTING）
        if self._pending_command_id is not None:
            self._publish_result(self._pending_command_id, "CANCELLED",
                                 {"reason": "COMMUNICATION_LOST"})
            self._pending_command_id = None
        log.info("Scenario-06 started: COMMUNICATION_LOST published, MQTT silent for %ds", seconds)

    def _resume_comms_if_due(self) -> bool:
        """End the silence when its timer elapsed: COMMUNICATION_RECOVERED, then patrol resumes.

        Runs at the top of every tick (before publishing), so the recovery event is always the
        first message after the silence. Returns True on the tick that resumed.
        """
        if not self._comms_silent or self._comms_silence_started_at is None:
            return False
        elapsed = time.monotonic() - self._comms_silence_started_at
        if elapsed < self._comms_silence_seconds:
            return False
        self._comms_silent = False
        self._comms_silence_started_at = None
        self._comms_silence_seconds = 0
        # back to the scenario that was active before the silence (a fire scenario started
        # during the silence keeps its own registry id)
        self._scenario_id = (
            VERDICT_SCENARIO_ID[self._fire_scenario["verdict"]]
            if self._fire_scenario["active"]
            else self._scenario_id_before_comms_lost
        )
        self._publish_event(
            "COMMUNICATION_RECOVERED",
            "INFO",
            {
                "reason": "SCENARIO_06_SILENCE_ELAPSED",
                "latitude": round(self._latitude, 6),
                "longitude": round(self._longitude, 6),
            },
        )
        log.info("Scenario-06 finished: COMMUNICATION_RECOVERED published, 1 Hz patrol resumed")
        return True

    def _comms_resume_in_seconds(self) -> int:
        """Remaining silence seconds (rounded up); 0 when not silent."""
        if not self._comms_silent or self._comms_silence_started_at is None:
            return 0
        remaining = self._comms_silence_seconds - (time.monotonic() - self._comms_silence_started_at)
        return max(0, math.ceil(remaining))

    # ------------------------------------------------------------------
    # fault injection (REST demo controls)
    # ------------------------------------------------------------------

    def inject_battery(self, percent: float) -> None:
        """Inject the battery level (0..100, validated by the caller) and emit the level event.

        Four-level thresholds (baseline ch.36): <= BATTERY_LOW_THRESHOLD (30) emits
        LOW_BATTERY (severity HIGH), <= BATTERY_CRITICAL_THRESHOLD (10) emits
        CRITICAL_BATTERY (severity CRITICAL); above 30 no event is emitted. The injected value
        is carried by the following state/telemetry publishes (1 Hz drain continues from it).
        """
        value = min(100.0, max(0.0, float(percent)))
        self._battery = value
        if value <= BATTERY_CRITICAL_THRESHOLD:
            self._publish_event("CRITICAL_BATTERY", "CRITICAL", {"battery": round(value, 1)})
        elif value <= BATTERY_LOW_THRESHOLD:
            self._publish_event("LOW_BATTERY", "HIGH", {"battery": round(value, 1)})
        log.info("Battery injected: %.1f%% (low<=%.0f, critical<=%.0f)",
                 value, BATTERY_LOW_THRESHOLD, BATTERY_CRITICAL_THRESHOLD)

    def inject_failure(self, failure_type: str) -> None:
        """Inject a fault: emit the matching severity HIGH UAV_EVENT.

        ``failure_type`` must be one of FAILURE_TYPES (GPS_LOST / RTK_LOST /
        CAMERA_ERROR / PAYLOAD_ERROR) or "RECOVER", which emits the recovery event
        (RTK_RECOVERED, severity INFO; COMMUNICATION_RECOVERED is reserved for the scenario-06
        comms recovery). Unknown types raise ValueError.
        """
        kind = str(failure_type).strip().upper()
        if kind == FAILURE_RECOVER_TYPE:
            recovered_from = self._last_failure_type or "UNKNOWN"
            self._last_failure_type = None
            self._publish_event(
                "RTK_RECOVERED", "INFO", {"reason": "FAULT_RECOVERED", "recoveredFrom": recovered_from}
            )
            log.info("Failure recovery injected: RTK_RECOVERED (recoveredFrom=%s)", recovered_from)
            return
        if kind not in FAILURE_TYPES:
            raise ValueError(f"failure type must be one of {FAILURE_TYPES + (FAILURE_RECOVER_TYPE,)}, "
                             f"got: {failure_type!r}")
        self._last_failure_type = kind
        self._publish_event(kind, "HIGH", {"reason": "FAULT_INJECTED_VIA_REST"})
        log.info("Failure injected: %s (severity HIGH)", kind)

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
            if self._fire_scenario["active"]:
                # 降落即结束火情采集（否则降落状态的无人机不会再执行 fire tick，
                # 场景残留会在下次起飞时把无人机拉回火点）
                self.stop_fire_scenario()
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
        elif command_type == "DROP_EXTINGUISHING_BALL":
            self._handle_drop_ball(command_id, payload)
        else:
            log.warning("Unknown commandType %s (commandId=%s)", command_type, command_id)
            self._publish_result(command_id, "FAILED", {"message": f"unknown commandType: {command_type}"})

    def _handle_drop_ball(self, command_id: str, payload: dict) -> None:
        """DROP_EXTINGUISHING_BALL（enums.yaml#CommandType 第10项，constants.yaml#extinguishing_ball）。

        机载安全校验按序：① 弹药余量 >0；② 与 payload 目标点距离 ≤ drop_max_radius_m。
        任一不满足即 FAILED（附原因，前端 toast 引导）；合法投放扣减余量并回 SUCCESS
        （result 带 remaining 供前端联动事件状态），最后一发投完自动 stop_fire_scenario
        恢复航线巡逻（处置完成自动归队）。
        """
        if self._balls_remaining <= 0:
            self._publish_result(command_id, "FAILED", {"message": "灭火弹已耗尽，无法投放"})
            return
        try:
            target_lat = float(payload["latitude"])
            target_lon = float(payload["longitude"])
        except (KeyError, TypeError, ValueError):
            self._publish_result(command_id, "FAILED",
                                 {"message": "payload 缺少 latitude/longitude 目标点"})
            return
        distance = _distance_m(self._latitude, self._longitude, target_lat, target_lon)
        if distance > EXTINGUISHING_BALL_DROP_MAX_RADIUS_M:
            self._publish_result(command_id, "FAILED", {
                "message": (f"距目标点 {distance:.0f}m，超出投放半径 "
                            f"{EXTINGUISHING_BALL_DROP_MAX_RADIUS_M:.0f}m，请先派单待无人机到位"),
            })
            return
        self._balls_remaining -= 1
        remaining = self._balls_remaining
        incident_id = str(payload.get("incidentId") or "")
        log.info("Extinguishing ball dropped: incident=%s target=(%.6f, %.6f) distance=%.0fm remaining=%d",
                 incident_id, target_lat, target_lon, distance, remaining)
        if remaining == 0:
            self.stop_fire_scenario()
        self._publish_result(command_id, "SUCCESS", {
            "incidentId": incident_id,
            "remaining": remaining,
            "capacity": EXTINGUISHING_BALL_CAPACITY,
        })

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
        # scenario-06: end the comms silence first so COMMUNICATION_RECOVERED is published
        # before this tick's state/telemetry (recovery event always comes first)
        self._resume_comms_if_due()

        self._moving = False
        if self._flight_status not in ("PAUSED", "LANDED"):
            if (
                self._flight_mode in ("GOTO", "RETURN_HOME")
                and self._target is not None
            ):
                # GOTO/RETURN_HOME 优先于火情场景（D6彩排修正）：演示中途重定位/返航时
                # 采集暂停，GOTO 到位后场景恢复（飞回火点继续采集）
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
                    self._target = None
                    if self._fire_scenario["active"]:
                        # GOTO 到位后：场景恢复（fire tick 将转场回火点继续采集）
                        self._fire_scenario["capturing"] = False
            elif self._fire_scenario["active"]:
                self._tick_fire_scenario()
            elif self._flight_mode == "WAYLINE":
                waypoint = self._waypoints[self._waypoint_index]
                arrived = not self._step_toward(waypoint[0], waypoint[1])
                self._moving = not arrived
                if arrived:
                    self._waypoint_index = (self._waypoint_index + 1) % len(self._waypoints)

        self._battery = max(BATTERY_FLOOR, self._battery - BATTERY_DRAIN_PER_TICK)

        if self._comms_silent:
            # scenario-06 silence: the simulation keeps running internally but nothing is
            # published on MQTT (state/telemetry/event/media all suppressed, see _publish)
            return

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
            if self._comms_silent:
                # scenario-06 silence: pause the capture timing, continue after recovery
                return
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
        self._publish_event(
            "MISSION_STARTED",
            "INFO",
            {
                "scenarioType": "FIRE",
                "latitude": fire_lat,
                "longitude": fire_lon,
            },
        )

    def _publish_event(self, event_type: str, severity: str, data: dict | None = None) -> None:
        """Publish one UAV_EVENT on uav/{deviceId}/event (structure per uav-event.json).

        severity follows the four alarm levels (INFO / WARNING / HIGH / CRITICAL).
        """
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
                "eventType": event_type,
                "severity": severity,
                "data": data or {},
            },
        }
        self._publish(f"uav/{DEVICE_ID}/event", message)
        log.info("UAV_EVENT published: %s (severity=%s) data=%s", event_type, severity, data or {})

    def _publish(self, topic: str, message: dict) -> None:
        if self._comms_silent:
            # scenario-06 silence: stop every MQTT publish (state/telemetry/event/media,
            # including REST-injected events and command results) until recovery
            log.debug("Comms silence active; dropped message on %s", topic)
            return
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
            "commsSilent": self._comms_silent,
            "resumeInSeconds": self._comms_resume_in_seconds(),
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
            "payload": {
                "extinguishingBall": {
                    "remaining": self._balls_remaining,
                    "capacity": EXTINGUISHING_BALL_CAPACITY,
                },
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

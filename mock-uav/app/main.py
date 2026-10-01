"""mock-uav FastAPI application.

The simulation loop starts automatically on the FastAPI startup event and can be
stopped/started again via the /simulator REST endpoints.
"""

import logging

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from typing import Literal

from .config import DEVICE_ID, LOG_LEVEL
from .simulator import (
    DEFAULT_SILENCE_SECONDS,
    FAILURE_RECOVER_TYPE,
    FAILURE_TYPES,
    MAX_SILENCE_SECONDS,
    Simulator,
)

logging.basicConfig(
    level=LOG_LEVEL,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

app = FastAPI(title="mock-uav", version="0.1.0")

simulator = Simulator()

# Baseline ch.61 DEMO scenario registry: scenario id -> fixed verdict.
# scenario-01 (normal patrol) is not listed; it maps to stopping the fire scenario.
SCENARIO_START_VERDICTS = {
    "scenario-02": "CONFIRMED",
    "scenario-04": "FALSE_ALARM",
}


class FireScenarioStartRequest(BaseModel):
    """Optional body of POST /simulator/scenarios/fire/start.

    latitude/longitude default to the built-in fire point; verdict selects the demo line:
      - "CONFIRMED" (default): media.metadata = {"scenarioType": "FIRE"}
      - "FALSE_ALARM": same flight/capture behaviour, media.metadata = {"scenarioType": "FALSE_ALARM"}
    """

    latitude: float | None = None
    longitude: float | None = None
    verdict: Literal["CONFIRMED", "FALSE_ALARM"] | None = None


class ScenarioStartRequest(BaseModel):
    """Optional body of POST /simulator/scenarios/{scenarioId}/start.

    {latitude, longitude} steer the fire point (scenario-02/04 only); {silenceSeconds} only
    applies to scenario-06 (UAV 断联): the MQTT silence duration, default 15 s, capped at 60 s.
    """

    latitude: float | None = None
    longitude: float | None = None
    silenceSeconds: int | None = None


class BatteryInjectRequest(BaseModel):
    """Body of POST /simulator/uavs/{uavId}/battery: the injected battery percentage (0..100)."""

    percent: float | None = None


class FailureInjectRequest(BaseModel):
    """Body of POST /simulator/uavs/{uavId}/failure: the injected fault type.

    GPS_LOST / RTK_LOST / CAMERA_ERROR / PAYLOAD_ERROR emit a severity HIGH UAV_EVENT;
    RECOVER emits the recovery event (RTK_RECOVERED, severity INFO).
    """

    type: str | None = None


def _require_simulated_uav(uav_id: str) -> JSONResponse | None:
    """Single-device mock: only DEVICE_ID exists; anything else is 40401 (aligned with backend)."""
    if (uav_id or "").strip() != DEVICE_ID:
        return JSONResponse(
            status_code=404, content={"code": 40401, "message": f"uav not found: {uav_id}"}
        )
    return None


def _resolve_silence_seconds(raw: int | None) -> tuple[int | None, JSONResponse | None]:
    """Validate the scenario-06 silence duration (1..MAX_SILENCE_SECONDS).

    Returns (seconds, None) on success or (None, 40001 response) on a bad value.
    """
    if raw is None:
        return None, None
    try:
        seconds = int(raw)
    except (TypeError, ValueError):
        return None, JSONResponse(
            status_code=400, content={"code": 40001, "message": "silenceSeconds must be an integer"}
        )
    if seconds < 1 or seconds > MAX_SILENCE_SECONDS:
        return None, JSONResponse(
            status_code=400,
            content={
                "code": 40001,
                "message": f"silenceSeconds must be within 1..{MAX_SILENCE_SECONDS}",
            },
        )
    return seconds, None


def _wrap(data):
    return {"code": 0, "message": "success", "data": data}


@app.on_event("startup")
async def _on_startup() -> None:
    await simulator.start()


@app.on_event("shutdown")
async def _on_shutdown() -> None:
    await simulator.stop()


@app.post("/simulator/start")
async def start_simulator():
    """(Re)start the simulation loop and MQTT publishing."""
    await simulator.start()
    return _wrap(simulator.status_snapshot())


@app.post("/simulator/stop")
async def stop_simulator():
    """Stop the simulation loop (no more UAV_STATE/UAV_TELEMETRY publishes)."""
    await simulator.stop()
    return _wrap(simulator.status_snapshot())


@app.get("/simulator/status")
async def simulator_status():
    """Current position / battery / flight state / target point / fire scenario.

    fireScenario contains {active, latitude, longitude, capturing, verdict}; verdict is the
    demo line of the last/running scenario ("CONFIRMED" or "FALSE_ALARM"). currentScenarioId
    is the active baseline ch.61 registry id ("scenario-01" on normal patrol,
    "scenario-02"/"scenario-04" while a fire scenario runs, "scenario-06" during the comms
    silence). commsSilent is true while the scenario-06 silence suppresses every MQTT publish,
    with resumeInSeconds counting down to the COMMUNICATION_RECOVERED recovery.
    """
    return _wrap(simulator.status_snapshot())


@app.post("/simulator/scenarios/fire/start")
async def start_fire_scenario(request: FireScenarioStartRequest | None = None):
    """Inject the fire scenario: fly to the fire point (15 m/s transit), auto-capture media on arrival.

    Body is optional, e.g. {"latitude": 30.1235, "longitude": 114.1285, "verdict": "FALSE_ALARM"};
    omit it entirely for the default fire point and the CONFIRMED line. Every UAV_MEDIA published
    while the scenario is active carries media.metadata = {"scenarioType": "FIRE" | "FALSE_ALARM"}.
    """
    latitude = request.latitude if request is not None else None
    longitude = request.longitude if request is not None else None
    verdict = request.verdict if request is not None else None
    simulator.start_fire_scenario(latitude=latitude, longitude=longitude, verdict=verdict)
    return _wrap(simulator.status_snapshot())


@app.post("/simulator/scenarios/fire/stop")
async def stop_fire_scenario():
    """Stop the fire scenario: stop media capture and restore the wayline patrol (8 m/s).

    Behaviour unchanged by the verdict lines; fireScenario.verdict keeps the last run's value.
    """
    simulator.stop_fire_scenario()
    return _wrap(simulator.status_snapshot())


@app.post("/simulator/scenarios/scenario-06/start")
async def start_comms_lost_scenario(request: ScenarioStartRequest | None = None):
    """Scenario-06 UAV 断联: COMMUNICATION_LOST -> MQTT silence -> COMMUNICATION_RECOVERED.

    Publishes the COMMUNICATION_LOST UAV_EVENT (severity HIGH, data carries the last known
    coordinates), then stops every MQTT publish (state/telemetry/event/media) for
    {silenceSeconds} (default 15 s, capped at 60 s). When the silence elapses,
    COMMUNICATION_RECOVERED (severity INFO) is published first and the 1 Hz patrol publishing
    resumes. REST keeps answering during the silence; /simulator/status reports
    commsSilent=true and resumeInSeconds. Idempotent: a repeated call only resets the timer.
    A running fire scenario pauses its capture timing and continues after recovery.
    """
    silence_seconds, error = _resolve_silence_seconds(
        request.silenceSeconds if request is not None else None
    )
    if error is not None:
        return error
    simulator.start_comms_lost_scenario(silence_seconds=silence_seconds)
    return _wrap(simulator.status_snapshot())


@app.post("/simulator/uavs/{uav_id}/battery")
async def inject_battery(uav_id: str, request: BatteryInjectRequest | None = None):
    """Inject the battery level ({percent}, 0..100) and emit the four-level battery events.

    <=30% emits LOW_BATTERY (severity HIGH), <=10% emits CRITICAL_BATTERY (severity CRITICAL);
    above 30% the level is injected without an event. The injected value is carried by the
    following 1 Hz state/telemetry publishes. During a scenario-06 silence the event publish
    is suppressed together with every other MQTT publish (the level is still injected).
    """
    not_found = _require_simulated_uav(uav_id)
    if not_found is not None:
        return not_found
    percent = request.percent if request is not None else None
    if percent is None:
        return JSONResponse(
            status_code=400,
            content={"code": 40001, "message": "percent is required (0..100)"},
        )
    if percent < 0 or percent > 100:
        return JSONResponse(
            status_code=400,
            content={"code": 40001, "message": "percent must be within 0..100"},
        )
    simulator.inject_battery(percent)
    return _wrap(simulator.status_snapshot())


@app.post("/simulator/uavs/{uav_id}/failure")
async def inject_failure(uav_id: str, request: FailureInjectRequest | None = None):
    """Inject a fault and emit the matching UAV_EVENT.

    {type} GPS_LOST / RTK_LOST / CAMERA_ERROR / PAYLOAD_ERROR -> severity HIGH event;
    {type: "RECOVER"} -> recovery event (RTK_RECOVERED, severity INFO). Unknown types return
    {code: 40001}. During a scenario-06 silence the event publish is suppressed together with
    every other MQTT publish.
    """
    not_found = _require_simulated_uav(uav_id)
    if not_found is not None:
        return not_found
    failure_type = request.type if request is not None else None
    if failure_type is None or failure_type.strip().upper() not in FAILURE_TYPES + (FAILURE_RECOVER_TYPE,):
        return JSONResponse(
            status_code=400,
            content={
                "code": 40001,
                "message": f"type must be one of {FAILURE_TYPES + (FAILURE_RECOVER_TYPE,)}",
            },
        )
    simulator.inject_failure(failure_type)
    return _wrap(simulator.status_snapshot())


@app.post("/simulator/scenarios/{scenario_id}/start")
async def start_scenario_by_id(scenario_id: str, request: ScenarioStartRequest | None = None):
    """Start a baseline ch.61 DEMO scenario by registry id (one-click for the demo script).

    - scenario-01 正常巡检: stop the fire scenario / restore wayline patrol (idempotent)
    - scenario-02 火情发现: fire scenario, verdict CONFIRMED, body optional {latitude, longitude}
    - scenario-04 误报:     fire scenario, verdict FALSE_ALARM, body optional {latitude, longitude}
    - scenario-06 UAV断联: COMMUNICATION_LOST -> MQTT silence (body optional {silenceSeconds},
      default 15 s, capped at 60 s) -> COMMUNICATION_RECOVERED, then the 1 Hz patrol resumes

    Returns {code: 0, data: {scenarioId, verdict?, fireScenario}}; unknown ids return
    {code: 40001, message: "unknown scenarioId"} (HTTP 400, aligned with backend ErrorCode).
    """
    key = (scenario_id or "").strip().lower()
    if key == "scenario-01":
        simulator.stop_fire_scenario()  # idempotent: also succeeds when already patrolling
        return _wrap({
            "scenarioId": key,
            "fireScenario": simulator.status_snapshot()["fireScenario"],
        })
    if key == "scenario-06":
        silence_seconds, error = _resolve_silence_seconds(
            request.silenceSeconds if request is not None else None
        )
        if error is not None:
            return error
        simulator.start_comms_lost_scenario(silence_seconds=silence_seconds)
        snapshot = simulator.status_snapshot()
        return _wrap({
            "scenarioId": key,
            "silenceSeconds": silence_seconds if silence_seconds is not None else DEFAULT_SILENCE_SECONDS,
            "commsSilent": snapshot["commsSilent"],
            "resumeInSeconds": snapshot["resumeInSeconds"],
            "fireScenario": snapshot["fireScenario"],
        })
    verdict = SCENARIO_START_VERDICTS.get(key)
    if verdict is None:
        return JSONResponse(status_code=400, content={"code": 40001, "message": "unknown scenarioId"})
    latitude = request.latitude if request is not None else None
    longitude = request.longitude if request is not None else None
    # 无 body 坐标时由模拟器按预设点位轮换（FIRE_PRESET_POINTS）：连续注入点位不同，
    # 且两两点位相距 > 火情去重半径 100m（误报线不会被合并进火情事件）
    simulator.start_fire_scenario(latitude=latitude, longitude=longitude, verdict=verdict)
    return _wrap({
        "scenarioId": key,
        "verdict": verdict,
        "fireScenario": simulator.status_snapshot()["fireScenario"],
    })

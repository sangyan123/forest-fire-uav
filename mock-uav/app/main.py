"""mock-uav FastAPI application.

The simulation loop starts automatically on the FastAPI startup event and can be
stopped/started again via the /simulator REST endpoints.
"""

import logging

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from typing import Literal

from .config import LOG_LEVEL
from .simulator import Simulator

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
    """Optional body of POST /simulator/scenarios/{scenarioId}/start ({latitude, longitude} only).

    The verdict is fixed by the scenario id in the registry (02 -> CONFIRMED, 04 -> FALSE_ALARM).
    """

    latitude: float | None = None
    longitude: float | None = None


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
    "scenario-02"/"scenario-04" while a fire scenario runs).
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


@app.post("/simulator/scenarios/{scenario_id}/start")
async def start_scenario_by_id(scenario_id: str, request: ScenarioStartRequest | None = None):
    """Start a baseline ch.61 DEMO scenario by registry id (one-click for the demo script).

    - scenario-01 正常巡检: stop the fire scenario / restore wayline patrol (idempotent)
    - scenario-02 火情发现: fire scenario, verdict CONFIRMED, body optional {latitude, longitude}
    - scenario-04 误报:     fire scenario, verdict FALSE_ALARM, body optional {latitude, longitude}

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
    verdict = SCENARIO_START_VERDICTS.get(key)
    if verdict is None:
        return JSONResponse(status_code=400, content={"code": 40001, "message": "unknown scenarioId"})
    latitude = request.latitude if request is not None else None
    longitude = request.longitude if request is not None else None
    simulator.start_fire_scenario(latitude=latitude, longitude=longitude, verdict=verdict)
    return _wrap({
        "scenarioId": key,
        "verdict": verdict,
        "fireScenario": simulator.status_snapshot()["fireScenario"],
    })

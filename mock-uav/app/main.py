"""mock-uav FastAPI application.

The simulation loop starts automatically on the FastAPI startup event and can be
stopped/started again via the /simulator REST endpoints.
"""

import logging

from fastapi import FastAPI
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


class FireScenarioStartRequest(BaseModel):
    """Optional body of POST /simulator/scenarios/fire/start.

    latitude/longitude default to the built-in fire point; verdict selects the demo line:
      - "CONFIRMED" (default): media.metadata = {"scenarioType": "FIRE"}
      - "FALSE_ALARM": same flight/capture behaviour, media.metadata = {"scenarioType": "FALSE_ALARM"}
    """

    latitude: float | None = None
    longitude: float | None = None
    verdict: Literal["CONFIRMED", "FALSE_ALARM"] | None = None


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
    demo line of the last/running scenario ("CONFIRMED" or "FALSE_ALARM").
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

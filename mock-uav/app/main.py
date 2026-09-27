"""mock-uav FastAPI application.

The simulation loop starts automatically on the FastAPI startup event and can be
stopped/started again via the /simulator REST endpoints.
"""

import logging

from fastapi import FastAPI

from .config import LOG_LEVEL
from .simulator import Simulator

logging.basicConfig(
    level=LOG_LEVEL,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

app = FastAPI(title="mock-uav", version="0.1.0")

simulator = Simulator()


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
    """Current position / battery / flight state / target point."""
    return _wrap(simulator.status_snapshot())

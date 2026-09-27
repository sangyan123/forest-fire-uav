"""mock-uav configuration (environment driven)."""

import os


def parse_mqtt_host(raw: str) -> tuple[str, int]:
    """Accepts 'mqtt:1883', 'tcp://mqtt:1883' or 'mqtt' and returns (host, port)."""
    value = (raw or "").strip()
    if "://" in value:
        value = value.split("://", 1)[1]
    host, _, port = value.partition(":")
    return (host or "mqtt"), (int(port) if port.isdigit() else 1883)


MQTT_HOST, MQTT_PORT = parse_mqtt_host(os.getenv("MQTT_HOST", "mqtt:1883"))

DEVICE_ID = os.getenv("UAV_DEVICE_ID", "UAV-001")

LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")

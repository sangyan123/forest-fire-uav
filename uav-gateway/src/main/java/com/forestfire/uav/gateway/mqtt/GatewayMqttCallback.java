package com.forestfire.uav.gateway.mqtt;

import java.nio.charset.StandardCharsets;

import com.forestfire.uav.gateway.service.CommandResultService;
import com.forestfire.uav.gateway.service.TelemetryIngestService;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT callback: (re)subscribes on connect and routes incoming messages.
 *
 * <ul>
 *   <li>{@code uav/{deviceId}/state}          -> TelemetryIngestService (backend telemetry-ingest)</li>
 *   <li>{@code uav/{deviceId}/command/result} -> CommandResultService  (backend command status PATCH)</li>
 * </ul>
 */
public class GatewayMqttCallback implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(GatewayMqttCallback.class);

    public static final String TOPIC_STATE_FILTER = "uav/+/state";
    public static final String TOPIC_COMMAND_RESULT_FILTER = "uav/+/command/result";

    private final TelemetryIngestService telemetryIngestService;
    private final CommandResultService commandResultService;

    private volatile MqttClient client;

    public GatewayMqttCallback(TelemetryIngestService telemetryIngestService,
                               CommandResultService commandResultService) {
        this.telemetryIngestService = telemetryIngestService;
        this.commandResultService = commandResultService;
    }

    public void setClient(MqttClient client) {
        this.client = client;
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        MqttClient mqttClient = this.client;
        if (mqttClient == null) {
            return;
        }
        try {
            mqttClient.subscribe(
                    new String[] {TOPIC_STATE_FILTER, TOPIC_COMMAND_RESULT_FILTER},
                    new int[] {1, 1});
            log.info("MQTT {}subscribed to [{}] and [{}] (QoS 1) on {}",
                    reconnect ? "re-" : "", TOPIC_STATE_FILTER, TOPIC_COMMAND_RESULT_FILTER, serverURI);
        } catch (MqttException e) {
            log.error("MQTT subscribe failed", e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {} (automatic reconnect in progress)",
                cause == null ? "unknown" : cause.toString());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Never let exceptions escape: paho shuts the client down on callback errors.
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            String[] parts = topic.split("/");
            if (parts.length == 3 && "uav".equals(parts[0]) && "state".equals(parts[2])) {
                telemetryIngestService.forward(parts[1], payload);
            } else if (parts.length == 4 && "uav".equals(parts[0])
                    && "command".equals(parts[2]) && "result".equals(parts[3])) {
                commandResultService.forward(parts[1], payload);
            } else {
                log.debug("Ignored message on unexpected topic {}", topic);
            }
        } catch (Exception e) {
            log.error("Failed to handle MQTT message on topic {}", topic, e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // publishing is synchronous here; nothing to do
    }
}

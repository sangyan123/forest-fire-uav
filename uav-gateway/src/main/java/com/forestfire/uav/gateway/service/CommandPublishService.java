package com.forestfire.uav.gateway.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds and publishes {@code UAV_COMMAND} messages (structure identical to
 * docs/protocol/uav-json-schema/examples/uav-command.json) to {@code uav/{deviceId}/command}.
 */
@Service
public class CommandPublishService {

    private static final Logger log = LoggerFactory.getLogger(CommandPublishService.class);

    private static final String SCHEMA_VERSION = "1.0";
    private static final String MESSAGE_TYPE_UAV_COMMAND = "UAV_COMMAND";
    private static final String SOURCE = "MOCK";
    private static final String PRIORITY = "HIGH";

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    private final AtomicLong sequence = new AtomicLong(1);

    public CommandPublishService(MqttClient mqttClient, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Assembles the UAV_COMMAND envelope and publishes it with QoS 1.
     *
     * @return the published command node (for the response envelope)
     */
    public ObjectNode publish(String deviceId, String commandId, String commandType, JsonNode payload)
            throws MqttException, JsonProcessingException {
        ObjectNode command = objectMapper.createObjectNode();
        command.put("schemaVersion", SCHEMA_VERSION);
        command.put("messageId", "CMD-MSG-" + shortRandom());
        command.put("messageType", MESSAGE_TYPE_UAV_COMMAND);
        command.put("deviceId", deviceId);
        command.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        command.put("source", SOURCE);
        command.put("sequence", sequence.getAndIncrement());
        command.put("commandId", commandId);
        command.put("commandType", commandType);
        command.put("priority", PRIORITY);
        command.set("payload", payload == null || payload.isNull()
                ? objectMapper.createObjectNode()
                : payload);

        MqttMessage message = new MqttMessage(objectMapper.writeValueAsBytes(command));
        message.setQos(1);
        mqttClient.publish("uav/" + deviceId + "/command", message);
        log.info("Published UAV_COMMAND {} (type={}, id={}) to uav/{}/command",
                command.get("messageId").asText(), commandType, commandId, deviceId);
        return command;
    }

    private String shortRandom() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}

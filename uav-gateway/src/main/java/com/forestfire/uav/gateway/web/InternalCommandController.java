package com.forestfire.uav.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forestfire.uav.gateway.service.CommandPublishService;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal command dispatch endpoint.
 *
 * <p>Request body: {@code {deviceId, commandId, commandType, payload}} — payload is a free-form
 * JSON node (e.g. a TargetPoint for GOTO) forwarded verbatim into the UAV_COMMAND message.</p>
 */
@RestController
@RequestMapping("/internal")
public class InternalCommandController {

    private final CommandPublishService commandPublishService;
    private final ObjectMapper objectMapper;

    public InternalCommandController(CommandPublishService commandPublishService, ObjectMapper objectMapper) {
        this.commandPublishService = commandPublishService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/commands")
    public ResponseEntity<ApiResponse<JsonNode>> dispatch(@RequestBody JsonNode request) throws MqttException,
            com.fasterxml.jackson.core.JsonProcessingException {
        String deviceId = requiredText(request, "deviceId");
        String commandId = requiredText(request, "commandId");
        String commandType = requiredText(request, "commandType");

        JsonNode published = commandPublishService.publish(deviceId, commandId, commandType, request.get("payload"));

        ObjectNode data = objectMapper.createObjectNode();
        data.put("topic", "uav/" + deviceId + "/command");
        data.set("message", published);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        if (value.contains("/") || value.contains("+") || value.contains("#") || value.contains(" ")) {
            throw new IllegalArgumentException("Field '" + field + "' contains invalid MQTT topic characters");
        }
        return value;
    }
}

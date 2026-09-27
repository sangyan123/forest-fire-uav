package com.forestfire.uav.gateway.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forestfire.uav.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Forwards UAV_COMMAND_RESULT messages from {@code uav/+/command/result} to
 * {@code PATCH {BACKEND_URL}/api/v1/commands/{commandId}/status} with body
 * {@code {status, message, executionTimeMs}}. Backend failures are logged only.
 */
@Service
public class CommandResultService {

    private static final Logger log = LoggerFactory.getLogger(CommandResultService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public CommandResultService(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void forward(String deviceId, String rawJson) {
        String commandId;
        String status;
        String bodyJson;
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            commandId = optionalText(root, "commandId");
            status = optionalText(root, "status");
            if (commandId == null || status == null) {
                log.warn("Command result on uav/{}/command/result missing commandId/status, ignored: {}",
                        deviceId, rawJson);
                return;
            }
            JsonNode result = root.path("result");
            ObjectNode body = objectMapper.createObjectNode();
            body.put("status", status);
            String message = result.hasNonNull("message")
                    ? result.get("message").asText()
                    : ("SUCCESS".equals(status) ? "success" : status);
            body.put("message", message);
            if (result.hasNonNull("executionTimeMs")) {
                body.put("executionTimeMs", result.get("executionTimeMs").asLong());
            }
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Failed to parse command result from device {}: {}. Raw: {}", deviceId, e.toString(), rawJson);
            return;
        }

        String url = properties.backendBaseUrl() + "/api/v1/commands/"
                + URLEncoder.encode(commandId, StandardCharsets.UTF_8) + "/status";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("Command status PATCH for {} failed: {} (backend unavailable).",
                                commandId, error.toString());
                    } else if (response.statusCode() >= 300) {
                        log.warn("Command status PATCH for {} returned HTTP {}. Body: {}",
                                commandId, response.statusCode(), response.body());
                    } else {
                        log.debug("Command status PATCH for {} succeeded (HTTP {})",
                                commandId, response.statusCode());
                    }
                });
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}

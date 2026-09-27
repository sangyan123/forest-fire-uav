package com.forestfire.uav.gateway.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.forestfire.uav.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Forwards raw UAV_STATE JSON from {@code uav/+/state} to
 * {@code POST {BACKEND_URL}/api/v1/uavs/{deviceId}/telemetry-ingest}.
 *
 * <p>Backend failures are logged only — the simulator keeps publishing, so the next
 * state message acts as the natural retry. The gateway never crashes on backend errors.</p>
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private static final String SERVICE_NAME = "uav-gateway";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final GatewayProperties properties;

    public TelemetryIngestService(GatewayProperties properties) {
        this.properties = properties;
    }

    public void forward(String deviceId, String rawJson) {
        String url = properties.backendBaseUrl() + "/api/v1/uavs/"
                + URLEncoder.encode(deviceId, StandardCharsets.UTF_8) + "/telemetry-ingest";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Service-Name", SERVICE_NAME)
                .POST(HttpRequest.BodyPublishers.ofString(rawJson, StandardCharsets.UTF_8))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("Telemetry ingest for {} failed: {}. Backend unavailable; "
                                + "next state cycle will retry naturally.", deviceId, error.toString());
                    } else if (response.statusCode() >= 300) {
                        log.warn("Telemetry ingest for {} returned HTTP {}. Body: {}",
                                deviceId, response.statusCode(), response.body());
                    } else {
                        log.debug("Telemetry ingest for {} succeeded (HTTP {})",
                                deviceId, response.statusCode());
                    }
                });
    }
}

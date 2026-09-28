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
 * Forwards raw UAV_MEDIA JSON from {@code uav/+/media} to
 * {@code POST {BACKEND_URL}/api/v1/uavs/{deviceId}/media-ingest}.
 *
 * <p>Backend failures are logged only — the gateway never crashes on backend errors.
 * Media messages are event-driven (not periodic like state), so a failed POST is
 * dropped after logging; the next capture produces a fresh message.</p>
 */
@Service
public class MediaIngestService {

    private static final Logger log = LoggerFactory.getLogger(MediaIngestService.class);

    private static final String SERVICE_NAME = "uav-gateway";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final GatewayProperties properties;

    public MediaIngestService(GatewayProperties properties) {
        this.properties = properties;
    }

    public void forward(String deviceId, String rawJson) {
        String url = properties.backendBaseUrl() + "/api/v1/uavs/"
                + URLEncoder.encode(deviceId, StandardCharsets.UTF_8) + "/media-ingest";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Service-Name", SERVICE_NAME)
                .POST(HttpRequest.BodyPublishers.ofString(rawJson, StandardCharsets.UTF_8))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("Media ingest for {} failed: {}. Backend unavailable; "
                                + "message dropped (next capture will retry).", deviceId, error.toString());
                    } else if (response.statusCode() >= 300) {
                        log.warn("Media ingest for {} returned HTTP {}. Body: {}",
                                deviceId, response.statusCode(), response.body());
                    } else {
                        log.debug("Media ingest for {} succeeded (HTTP {})",
                                deviceId, response.statusCode());
                    }
                });
    }
}

package com.forestfire.uav.gateway.config;

import java.util.UUID;

import com.forestfire.uav.gateway.mqtt.GatewayMqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQTT client wiring: clientId {@code uav-gateway-{random}}, cleanSession=true.
 * The actual (retrying) connect happens in {@code MqttConnector} once the app is ready.
 */
@Configuration
public class MqttConfig {

    @Bean
    public GatewayMqttCallback gatewayMqttCallback(
            com.forestfire.uav.gateway.service.TelemetryIngestService telemetryIngestService,
            com.forestfire.uav.gateway.service.MediaIngestService mediaIngestService,
            com.forestfire.uav.gateway.service.CommandResultService commandResultService) {
        return new GatewayMqttCallback(telemetryIngestService, mediaIngestService, commandResultService);
    }

    @Bean
    public MqttClient mqttClient(GatewayProperties properties, GatewayMqttCallback callback) throws MqttException {
        String clientId = "uav-gateway-" + UUID.randomUUID().toString().substring(0, 8);
        MqttClient client = new MqttClient(properties.resolvedBrokerUri(), clientId, new MemoryPersistence());
        callback.setClient(client);
        client.setCallback(callback);
        return client;
    }
}

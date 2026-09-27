package com.forestfire.uav.gateway.mqtt;

import com.forestfire.uav.gateway.config.GatewayProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Connects to the MQTT broker once the application is ready, retrying every 5 seconds
 * until the broker accepts the connection (the broker may not be up first in DEV).
 * Paho's automaticReconnect handles any later drops; GatewayMqttCallback re-subscribes
 * from its connectComplete hook.
 */
@Component
public class MqttConnector {

    private static final Logger log = LoggerFactory.getLogger(MqttConnector.class);

    private static final long RETRY_INTERVAL_MS = 5_000L;

    private final MqttClient client;
    private final GatewayProperties properties;

    public MqttConnector(MqttClient client, GatewayProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread connector = new Thread(this::connectWithRetry, "mqtt-connector");
        connector.setDaemon(true);
        connector.start();
    }

    private void connectWithRetry() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(30);
        options.setKeepAliveInterval(60);
        while (!client.isConnected()) {
            try {
                log.info("Connecting to MQTT broker {} (clientId={})",
                        properties.resolvedBrokerUri(), client.getClientId());
                client.connect(options);
                log.info("Connected to MQTT broker {}", properties.resolvedBrokerUri());
                return;
            } catch (MqttException e) {
                log.warn("MQTT connect failed: {}. Retrying in {} ms",
                        e.getMessage(), RETRY_INTERVAL_MS);
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}

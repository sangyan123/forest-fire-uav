package com.forestfire.uav.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * UAV Gateway entry point.
 *
 * <p>Subscribes {@code uav/+/state} and {@code uav/+/command/result} on the MQTT broker,
 * forwards them to the backend REST API, and exposes {@code POST /internal/commands}
 * to publish {@code UAV_COMMAND} messages down to UAV devices.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UavGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(UavGatewayApplication.class, args);
    }
}

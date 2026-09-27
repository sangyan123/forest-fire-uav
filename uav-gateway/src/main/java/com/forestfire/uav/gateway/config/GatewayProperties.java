package com.forestfire.uav.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway configuration bound from application.yml / environment variables.
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Mqtt mqtt = new Mqtt();

    private String backendUrl = "http://backend:8080";

    public Mqtt getMqtt() {
        return mqtt;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    /** Backend base URL without trailing slash. */
    public String backendBaseUrl() {
        String url = backendUrl == null ? "" : backendUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** Broker URI, prefixed with tcp:// when the scheme is missing. */
    public String resolvedBrokerUri() {
        String uri = mqtt.getBrokerUri();
        if (uri == null || uri.isBlank()) {
            return "tcp://mqtt:1883";
        }
        uri = uri.trim();
        if (!uri.contains("://")) {
            uri = "tcp://" + uri;
        }
        return uri;
    }

    public static class Mqtt {

        private String brokerUri = "tcp://mqtt:1883";

        public String getBrokerUri() {
            return brokerUri;
        }

        public void setBrokerUri(String brokerUri) {
            this.brokerUri = brokerUri;
        }
    }
}

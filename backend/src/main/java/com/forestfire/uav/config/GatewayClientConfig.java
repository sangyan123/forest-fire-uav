package com.forestfire.uav.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * UAV Gateway 下发客户端（POST {GATEWAY_URL}/internal/commands）。
 */
@Configuration
public class GatewayClientConfig {

    @Bean
    public RestClient gatewayRestClient(
            @Value("${gateway.url:http://uav-gateway:9000}") String gatewayUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        return RestClient.builder()
                .baseUrl(gatewayUrl)
                .requestFactory(factory)
                .build();
    }
}

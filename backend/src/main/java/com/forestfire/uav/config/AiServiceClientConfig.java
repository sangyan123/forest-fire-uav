package com.forestfire.uav.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI Service 调用客户端（POST {AI_SERVICE_URL}/ai/v1/detection|localization|verification）。
 */
@Configuration
public class AiServiceClientConfig {

    @Bean
    public RestClient aiServiceRestClient(
            @Value("${ai-service.url:http://ai-service:8000}") String aiServiceUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder()
                .baseUrl(aiServiceUrl)
                .requestFactory(factory)
                .build();
    }
}

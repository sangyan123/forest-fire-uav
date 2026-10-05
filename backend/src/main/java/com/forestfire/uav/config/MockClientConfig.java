package com.forestfire.uav.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * mock-uav 模拟器查询客户端（GET {MOCK_URL}/simulator/status，定时巡逻收敛循环用）。
 */
@Configuration
public class MockClientConfig {

    @Bean
    public RestClient mockRestClient(
            @Value("${mock.url:http://mock-uav:8002}") String mockUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        return RestClient.builder()
                .baseUrl(mockUrl)
                .requestFactory(factory)
                .build();
    }
}

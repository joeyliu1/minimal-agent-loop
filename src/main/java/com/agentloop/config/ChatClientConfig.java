package com.agentloop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * Spring AI Alibaba 1.1.2.2 exposes spring.ai.dashscope.read-timeout but
     * does not apply it to the RestClient used by the chat model. Configure
     * the request factory explicitly so slower model responses are not cut off
     * by the SDK's 10-second default.
     */
    @Bean
    public RestClientCustomizer dashScopeTimeoutCustomizer(
            @Value("${spring.ai.dashscope.read-timeout:45}") int readTimeoutSeconds) {

        int safeReadTimeout = Math.max(1, readTimeoutSeconds);
        return builder -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            JdkClientHttpRequestFactory requestFactory =
                    new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(safeReadTimeout));
            builder.requestFactory(requestFactory);
        };
    }
}

package com.example.policyrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({ClientConfiguration.GroqProperties.class, ClientConfiguration.ChromaProperties.class})
public class ClientConfiguration {

    @Bean
    RestClient.Builder restClientBuilder() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory);
    }

    @ConfigurationProperties("app.groq")
    public record GroqProperties(String baseUrl, String apiKey, String model) {
    }

    @ConfigurationProperties("app.chroma")
    public record ChromaProperties(String baseUrl, String tenant, String database, String collection) {
    }
}

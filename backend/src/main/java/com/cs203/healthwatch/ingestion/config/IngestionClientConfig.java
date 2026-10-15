package com.cs203.healthwatch.ingestion.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class IngestionClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder, IngestionHttpProperties props) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        return builder.requestFactory(factory).build();
    }

    /**
     * Singapore time, which is how data.gov.sg dates its readings. Injected rather than calling now() directly,
     * so tests can fix "today".
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Singapore"));
    }
}
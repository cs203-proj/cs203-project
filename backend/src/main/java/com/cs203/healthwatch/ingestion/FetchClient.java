package com.cs203.healthwatch.ingestion;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.cs203.healthwatch.ingestion.config.IngestionHttpProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FetchClient {
    private static final Logger log = LoggerFactory.getLogger(FetchClient.class);

    private final RestClient restClient;
    private final IngestionHttpProperties props;

    public String fetch(String url) {

        int maxAttempts = props.maxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("rate limited (attempt {}): {}", attempt, e.getMessage());
                if (!backoff(attempt, maxAttempts)) return null;
            } catch (HttpClientErrorException e) {
                log.error("fetch failed with client error {}: {}", e.getStatusCode(), e.getMessage());
                return null; // genuinely non-retryable (400/401/404 etc.)
            } catch (RestClientException e) {
                log.error("fetch attempt {} failed: {}", attempt, e.getMessage());
                if (!backoff(attempt, maxAttempts)) return null;
            }
        }

        return null;
    }

    private boolean backoff(int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) return true; // last attempt, no need to wait — loop exits next
        try {
            Thread.sleep(props.initialBackoff().toMillis() * (1L << (attempt - 1)));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
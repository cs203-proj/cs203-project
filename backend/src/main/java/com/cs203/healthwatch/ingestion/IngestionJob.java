package com.cs203.healthwatch.ingestion;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(
        prefix = "ingestion.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
public class IngestionJob {
    private static final Logger log = LoggerFactory.getLogger(IngestionJob.class);

    private final List<IngestionService> services;

    @Scheduled(fixedDelayString = "${ingestion.scheduling.fixed-delay-ms}")
    public void run() {
        log.info("starting ingestion cycle");
        for (IngestionService service : services) {
            try {
                service.ingestLatest();
            } catch (Exception e) {
                log.error("ingestion service {} failed: {}", service.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }
}

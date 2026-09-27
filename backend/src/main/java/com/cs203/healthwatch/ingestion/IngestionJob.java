package com.cs203.healthwatch.ingestion;

import com.cs203.healthwatch.detection.DetectionOrchestrator;
import com.cs203.healthwatch.detection.DetectionProperties;

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
    private final DetectionOrchestrator detectionOrchestrator;
    private final DetectionProperties detectionProperties;

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

        // S1-6: run detection after each pull. Safe to run even if the pull failed:
        // with no new readings it either finds nothing or skips via the open-event dedupe.
        try {
            detectionOrchestrator.runDetection(
                    detectionProperties.region(), detectionProperties.signalType(), false);
        } catch (Exception e) {
            log.error("detection failed: {}", e.getMessage(), e);
        }
    }
}

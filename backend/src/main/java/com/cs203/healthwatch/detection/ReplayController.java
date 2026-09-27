package com.cs203.healthwatch.detection;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Admin-only: enforced by the "/demo/**" rule in SecurityConfig
@Slf4j
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class ReplayController {

    // Separate data source so replay readings never collide with live readings
    // on the (source_id, region, observed_at) unique constraint
    private static final String REPLAY_SOURCE_NAME = "replay_fixture";

    // TODO: replace with CG-62's real Sept 2026 haze values (spike Sept 3-4, peak ~139)
    // once the baseline service is wired up, and check they cross the threshold against it
    private static final double[] FIXTURE_VALUES = {120, 125, 130};

    private final DetectionOrchestrator detectionOrchestrator;
    private final DetectionProperties config;
    private final ReadingRepository readingRepository;
    private final DataSourceRegistry dataSourceRegistry;

    @PostMapping("/replay")
    public ResponseEntity<String> replay() {
        String region = config.region();
        String signalType = config.signalType();
        UUID sourceId = dataSourceRegistry.resolve(REPLAY_SOURCE_NAME, signalType);

        // One hour apart, ending at the current hour: ordered, unique per timestamp,
        // within maxGap, and the latest N readings for the region
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
        int n = FIXTURE_VALUES.length;
        for (int i = 0; i < n; i++) {
            Instant observedAt = end.minus(Duration.ofHours(n - 1 - i));
            insertFixtureReading(sourceId, region, FIXTURE_VALUES[i], observedAt);
        }

        // replay = true: uses only synthetic readings, and tags the event before saving
        Optional<DetectedEvent> created = detectionOrchestrator.runDetection(region, signalType, true);

        return created
                .map(e -> ResponseEntity.ok("Replay created event " + e.getId()))
                .orElse(ResponseEntity.ok("Replay ran, no event created (no baseline yet, "
                        + "threshold not crossed, or a replay event is already open)"));
    }

    private void insertFixtureReading(UUID sourceId, String region, double value, Instant observedAt) {
        Reading r = new Reading();
        r.setSourceId(sourceId);
        r.setRegion(region);
        r.setValue(value);
        r.setObservedAt(observedAt.atOffset(ZoneOffset.UTC));
        r.setIngestedAt(OffsetDateTime.now());
        r.setMalformed(false);
        r.setSynthetic(true); // fabricated demo data, excluded from live detection
        try {
            readingRepository.save(r);
        } catch (DataIntegrityViolationException e) {
            // replay already ran this hour, so this fixture reading already exists
            log.info("replay reading already present for {} {}", region, observedAt);
        }
    }
}

package com.cs203.healthwatch.detection;

import com.cs203.healthwatch.events.DetectedEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class ReplayController {

    private static final String SIGNAL_TYPE = "AQI";

    // TODO: replace with CG-62's real Sept 2026 haze values (spike Sept 3-4, peak ~139)
    // once wired up, and check they cross the threshold against the real baseline
    private static final double[] FIXTURE_VALUES = {120, 125, 130};

    private final DetectionOrchestrator detectionOrchestrator;
    private final DetectionProperties config;
    // inject once CG-9 merges:
    // private final ReadingRepository readingRepository;

    @PostMapping("/replay")
    @PreAuthorize("hasRole('ADMIN')") // only enforced once S1-3 adds @EnableMethodSecurity
    public ResponseEntity<String> replay() {
        String region = config.region();

        // One hour apart, ending at the current hour: ordered, unique per timestamp,
        // within maxGap, and the latest N readings for the region
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
        int n = FIXTURE_VALUES.length;
        for (int i = 0; i < n; i++) {
            Instant observedAt = end.minus(Duration.ofHours(n - 1 - i));
            insertFixtureReading(region, SIGNAL_TYPE, FIXTURE_VALUES[i], observedAt);
        }

        // replay = true is set before saving, so a live event can never be mislabelled
        Optional<DetectedEvent> created = detectionOrchestrator.runDetection(region, SIGNAL_TYPE, true);

        return created
                .map(e -> ResponseEntity.ok("Replay created event " + e.getId()))
                .orElse(ResponseEntity.ok(
                        "Replay ran, no event created (threshold not crossed, or a replay event is already open)"));
    }

    private void insertFixtureReading(String region, String signalType, double value, Instant observedAt) {
        // TODO: save via CG-9's ReadingRepository with is_synthetic = true
        // Blocked on:
        //   - branch CG-9-source_ingestion being merged
        // Check with CG-62:
        //   - baseline service excludes is_synthetic rows, so replays don't shift the real baseline
        throw new UnsupportedOperationException("wire this to CG-9's ReadingRepository — see TODO above");
    }
}

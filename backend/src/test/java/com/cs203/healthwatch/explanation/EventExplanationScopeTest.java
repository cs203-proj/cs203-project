package com.cs203.healthwatch.explanation;

import static com.cs203.healthwatch.explanation.TestExplanations.hourly;
import static com.cs203.healthwatch.explanation.TestExplanations.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.detection.BaselineRepository;
import com.cs203.healthwatch.detection.BaselineSnapshot;
import com.cs203.healthwatch.detection.DetectionProperties;
import com.cs203.healthwatch.detection.SpatialScope;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CG-169: the explanation row stores the scope computed in code; a failure there never blocks the explanation. */
class EventExplanationScopeTest {

    private static final Instant DETECTED = Instant.parse("2026-10-18T03:00:00Z");
    private static final BaselineSnapshot BASELINE = new BaselineSnapshot("central", "pm25", 18.0, 5.0); // above > 33
    private static final AiExplanation ANSWER = new AiExplanation(Assessment.GENUINE_TREND, "cause", "text",
            List.of("e"), List.of("a"));
    private static final DetectionProperties DETECTION = new DetectionProperties("central", "pm25", "air_quality",
            3.0, 3, Duration.ofMinutes(90), null);
    private static final PmSourceProperties PM = new PmSourceProperties("n", "u", "air_quality",
            List.of("central", "north", "south", "east", "west").stream()
                    .map(PmSourceProperties.Region::new).toList());

    private ExplanationClient client;
    private EventExplanationRepository repo;
    private ReadingRepository readingRepo;
    private BaselineRepository baselineRepo;
    private DetectedEvent event;

    @BeforeEach
    void setUp() {
        client = mock(ExplanationClient.class);
        repo = mock(EventExplanationRepository.class);
        readingRepo = mock(ReadingRepository.class);
        baselineRepo = mock(BaselineRepository.class);
        when(client.modelName()).thenReturn("m");
        when(client.explain(any())).thenReturn(ANSWER);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(baselineRepo.latestBaseline(anyString(), anyString(), anyString())).thenReturn(Optional.of(BASELINE));

        event = new DetectedEvent();
        event.setId(UUID.randomUUID());
        event.setRegion("central");
        event.setSignalType("pm25");
        event.setDetectedAt(DETECTED);
        event.setDeviation(10.0);
        event.setStatus(EventStatus.NEW);
    }

    private static Reading reading(String region, double value) {
        Reading r = new Reading();
        r.setRegion(region);
        r.setValue(value);
        r.setObservedAt(DETECTED.minus(Duration.ofMinutes(20)).atOffset(ZoneOffset.UTC));
        return r;
    }

    private EventExplanation explain() {
        var service = new EventExplanationService(client, repo, mock(EventRepository.class), props(true, "key"),
                readingRepo, baselineRepo, DETECTION, PM, Clock.fixed(DETECTED, ZoneOffset.UTC));
        return service.explainNewEvent(event, BASELINE, hourly(DETECTED, 18, 20, 25, 45, 55, 70), 3.0, 3)
                .orElseThrow();
    }

    private void others(Map<String, Double> values) {
        List<Reading> list = new ArrayList<>();
        values.forEach((region, v) -> list.add(reading(region, v)));
        when(readingRepo.findOtherRegionsValidBetween(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(list);
    }

    @Test
    void local_storesScopeAndBothRegionLists() {
        others(Map.of("north", 20.0, "south", 25.0, "east", 22.0, "west", 19.0));

        EventExplanation row = explain();

        assertThat(row.getScope()).isEqualTo(SpatialScope.LOCAL);
        assertThat(row.getRegionsAbove()).containsExactly("central");
        assertThat(row.getRegionsCompared()).containsExactly("central", "east", "north", "south", "west");
    }

    @Test
    void missingRegions_isMultiRegion() {
        others(Map.of("north", 60.0, "south", 20.0)); // east and west did not report

        EventExplanation row = explain();

        assertThat(row.getScope()).isEqualTo(SpatialScope.MULTI_REGION);
        assertThat(row.getRegionsAbove()).containsExactly("central", "north");
        assertThat(row.getRegionsCompared()).containsExactly("central", "north", "south");
    }

    @Test
    void allRegionsAbove_isIslandWide() {
        others(Map.of("north", 60.0, "south", 50.0, "east", 40.0, "west", 90.0));

        assertThat(explain().getScope()).isEqualTo(SpatialScope.ISLAND_WIDE);
    }

    @Test
    void failureInsideScope_storesUnknown_andStillExplains() {
        when(readingRepo.findOtherRegionsValidBetween(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        EventExplanation row = explain();

        assertThat(row.getStatus()).isEqualTo(ExplanationStatus.OK);
        assertThat(row.getScope()).isEqualTo(SpatialScope.UNKNOWN);
        assertThat(row.getRegionsAbove()).isEmpty();
        assertThat(row.getRegionsCompared()).isEmpty();
    }
}

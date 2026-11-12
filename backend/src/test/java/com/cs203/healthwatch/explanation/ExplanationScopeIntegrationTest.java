package com.cs203.healthwatch.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.detection.BaselineRepository;
import com.cs203.healthwatch.detection.BaselineSnapshot;
import com.cs203.healthwatch.detection.DetectionProperties;
import com.cs203.healthwatch.detection.ReadingSnapshot;
import com.cs203.healthwatch.detection.SpatialScope;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * CG-174: the explanation flow against real readings and a real explanation table (H2). Baselines and the AI are
 * mocked. Baseline for every region: median 18, spread 5, so a reading is above the threshold when it is over 33.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ExplanationScopeIntegrationTest {

    private static final String TYPE = "air_quality";
    private static final Instant DETECTED = Instant.parse("2026-10-18T03:00:00Z");
    private static final BaselineSnapshot BASELINE = new BaselineSnapshot("central", "pm25", 18.0, 5.0);
    private static final DetectionProperties DETECTION = new DetectionProperties("central", "pm25", TYPE,
            3.0, 3, Duration.ofMinutes(90), null);
    private static final PmSourceProperties PM = new PmSourceProperties("n", "u", TYPE,
            List.of("central", "north", "south", "east", "west").stream()
                    .map(PmSourceProperties.Region::new).toList());

    @Autowired TestEntityManager em;
    @Autowired ReadingRepository readingRepository;
    @Autowired EventExplanationRepository explanations;
    @Autowired EventRepository events;

    private EventExplanationService service;
    private UUID source;

    @BeforeEach
    void setUp() {
        ExplanationClient client = mock(ExplanationClient.class);
        BaselineRepository baselines = mock(BaselineRepository.class);
        when(client.modelName()).thenReturn("m");
        when(client.explain(any())).thenReturn(new AiExplanation(Assessment.GENUINE_TREND, "cause", "text",
                List.of("e"), List.of("a"), "summary"));
        when(baselines.latestBaseline(anyString(), anyString(), anyString())).thenReturn(Optional.of(BASELINE));
        service = new EventExplanationService(client, explanations, events, TestExplanations.props(true, "key"),
                readingRepository, baselines, DETECTION, PM, Clock.fixed(DETECTED, ZoneOffset.UTC));
        source = em.persistAndFlush(new DataSource("datagovsg_pm25", TYPE)).getId();
    }

    private void reading(String region, double value) {
        Reading r = new Reading();
        r.setSourceId(source);
        r.setRegion(region);
        r.setValue(value);
        r.setObservedAt(DETECTED.minus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC));
        em.persistAndFlush(r);
    }

    /** Seeds the given regions' readings, then explains a central event with three hourly central readings. */
    private EventExplanation explain(Map<String, Double> byRegion) {
        byRegion.forEach(this::reading);
        DetectedEvent event = TestEvents.event(8.0, EventStatus.NEW, DETECTED.toString());
        event.setRegion("central");
        event.setSignalType("pm25");
        em.persistAndFlush(event);
        List<ReadingSnapshot> central = List.of(
                new ReadingSnapshot("central", "pm25", 50, DETECTED.minus(Duration.ofHours(2))),
                new ReadingSnapshot("central", "pm25", 60, DETECTED.minus(Duration.ofHours(1))),
                new ReadingSnapshot("central", "pm25", byRegion.get("central"), DETECTED.minus(Duration.ofMinutes(10))));
        service.explainNewEvent(event, BASELINE, central, 3.0, 3);
        em.flush();
        em.clear();
        return explanations.findByEventId(event.getId()).orElseThrow();
    }

    @Test
    void local_onlyCentralAbove() {
        EventExplanation row = explain(Map.of("central", 70.0, "north", 20.0, "south", 22.0, "east", 19.0,
                "west", 25.0));

        assertThat(row.getScope()).isEqualTo(SpatialScope.LOCAL);
        assertThat(row.getRegionsAbove()).containsExactly("central");
        assertThat(row.getRegionsCompared()).containsExactly("central", "east", "north", "south", "west");
    }

    @Test
    void multiRegion_withMissingRegions_eastAndWestDidNotReport() {
        EventExplanation row = explain(Map.of("central", 70.0, "north", 55.0, "south", 21.0));

        assertThat(row.getScope()).isEqualTo(SpatialScope.MULTI_REGION);
        assertThat(row.getRegionsAbove()).containsExactly("central", "north");
        assertThat(row.getRegionsCompared()).containsExactly("central", "north", "south");
    }

    @Test
    void islandWide_everyRegionAbove() {
        EventExplanation row = explain(Map.of("central", 70.0, "north", 55.0, "south", 41.0, "east", 38.0,
                "west", 90.0));

        assertThat(row.getScope()).isEqualTo(SpatialScope.ISLAND_WIDE);
        assertThat(row.getRegionsAbove()).containsExactly("central", "east", "north", "south", "west");
        assertThat(row.getScopeSummary()).isEqualTo("summary");
    }

    @Test
    void readingOutsideTheWindow_isIgnored() {
        // north's only reading is 3 hours old: outside (eventTime - 90m, eventTime], so only 2 regions compare
        Reading old = new Reading();
        old.setSourceId(source);
        old.setRegion("north");
        old.setValue(60.0);
        old.setObservedAt(DETECTED.minus(Duration.ofHours(3)).atOffset(ZoneOffset.UTC));
        em.persistAndFlush(old);

        EventExplanation row = explain(Map.of("central", 70.0, "south", 21.0));

        assertThat(row.getScope()).isEqualTo(SpatialScope.UNKNOWN);
        assertThat(row.getRegionsCompared()).isEmpty();
    }
}

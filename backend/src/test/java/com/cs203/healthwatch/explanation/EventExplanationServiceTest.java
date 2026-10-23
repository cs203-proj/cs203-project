package com.cs203.healthwatch.explanation;

import static com.cs203.healthwatch.explanation.TestExplanations.hourly;
import static com.cs203.healthwatch.explanation.TestExplanations.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.detection.BaselineSnapshot;
import com.cs203.healthwatch.detection.ReadingSnapshot;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The AI is always mocked here: CI never calls a live API. */
class EventExplanationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T03:10:00Z");
    private static final BaselineSnapshot BASELINE = new BaselineSnapshot("central", "pm25", 18.0, 5.0);
    private static final AiExplanation ANSWER = new AiExplanation(Assessment.GENUINE_TREND,
            "Consistent with regional haze", "PM2.5 rose steadily.", List.of("45, 55, 70 vs median 18"),
            List.of("Station reports correctly"));

    private ExplanationClient client;
    private EventExplanationRepository repo;
    private EventRepository events;
    private DetectedEvent event;

    @BeforeEach
    void setUp() {
        client = mock(ExplanationClient.class);
        repo = mock(EventExplanationRepository.class);
        events = mock(EventRepository.class);
        when(client.modelName()).thenReturn("groq-test-model");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        event = new DetectedEvent();
        event.setId(UUID.randomUUID());
        event.setRegion("central");
        event.setSignalType("pm25");
        event.setDetectedAt(Instant.parse("2026-10-18T03:00:00Z"));
        event.setDeviation(10.4);
        event.setStatus(EventStatus.NEW);
    }

    private EventExplanationService service(boolean enabled) {
        return new EventExplanationService(client, repo, events, props(enabled, "key"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private List<ReadingSnapshot> sixFreshReadings() {
        return hourly(Instant.parse("2026-10-18T03:00:00Z"), 18, 20, 25, 45, 55, 70);
    }

    private Optional<EventExplanation> explain(EventExplanationService service, List<ReadingSnapshot> readings) {
        return service.explainNewEvent(event, BASELINE, readings, 3.0, 3);
    }

    @Test
    void success_storesExplanationEvidenceAssumptionsAndDataQuality() {
        when(client.explain(any())).thenReturn(ANSWER);

        EventExplanation saved = explain(service(true), sixFreshReadings()).orElseThrow();

        assertThat(saved.getEventId()).isEqualTo(event.getId());
        assertThat(saved.getStatus()).isEqualTo(ExplanationStatus.OK);
        assertThat(saved.getAssessment()).isEqualTo(Assessment.GENUINE_TREND);
        assertThat(saved.getLikelyCause()).isEqualTo("Consistent with regional haze");
        assertThat(saved.getExplanation()).isEqualTo("PM2.5 rose steadily.");
        assertThat(saved.getEvidence()).containsExactly("45, 55, 70 vs median 18");
        assertThat(saved.getAssumptions()).containsExactly("Station reports correctly");
        assertThat(saved.isDataComplete()).isTrue();
        assertThat(saved.getDataQualityNote()).startsWith("Based on complete data");
        assertThat(saved.getModel()).isEqualTo("groq-test-model");
        assertThat(saved.getError()).isNull();
    }

    @Test
    void sendsTheEventFactsAndComputedDataQualityToTheAi() {
        when(client.explain(any())).thenReturn(ANSWER);

        explain(service(true), sixFreshReadings());

        ArgumentCaptor<ExplanationContext> sent = ArgumentCaptor.forClass(ExplanationContext.class);
        verify(client).explain(sent.capture());
        ExplanationContext c = sent.getValue();
        assertThat(c.eventId()).isEqualTo(event.getId());
        assertThat(c.deviation()).isEqualTo(10.4);
        assertThat(c.baselineMedian()).isEqualTo(18.0);
        assertThat(c.baselineScaledMad()).isEqualTo(5.0);
        assertThat(c.readings()).hasSize(6);
        assertThat(c.dataQuality().complete()).isTrue();
    }

    @Test
    void partialData_isStatedExplicitlyInTheStoredRow() {
        when(client.explain(any())).thenReturn(ANSWER);
        List<ReadingSnapshot> fourReadings = hourly(Instant.parse("2026-10-18T03:00:00Z"), 25, 45, 55, 70);

        EventExplanation saved = explain(service(true), fourReadings).orElseThrow();

        assertThat(saved.isDataComplete()).isFalse();
        assertThat(saved.getDataQualityNote())
                .isEqualTo("Based on partial data: 4 of 6 expected readings in the last 6 hours.");
    }

    @Test
    void liveEvent_withOldReadings_isMarkedStale() {
        when(client.explain(any())).thenReturn(ANSWER);
        List<ReadingSnapshot> old = hourly(NOW.minus(Duration.ofHours(5)), 18, 45, 55, 70);

        EventExplanation saved = explain(service(true), old).orElseThrow();

        assertThat(saved.getDataQualityNote()).contains("Data is stale");
    }

    @Test
    void replayEvent_judgesFreshnessAtDetectionTime_notNow() {
        when(client.explain(any())).thenReturn(ANSWER);
        event.setReplay(true);
        event.setDetectedAt(Instant.parse("2026-09-04T06:00:00Z"));
        List<ReadingSnapshot> september = hourly(Instant.parse("2026-09-04T06:00:00Z"), 18, 20, 25, 45, 55, 70);

        EventExplanation saved = explain(service(true), september).orElseThrow();

        assertThat(saved.isDataComplete()).isTrue();
        assertThat(saved.getDataQualityNote()).doesNotContain("stale");
    }

    @Test
    void aiFailure_storesFailedRow_doesNotThrow_andLeavesEventNew() {
        when(client.explain(any())).thenThrow(new ExplanationException("Groq call failed: 429 Too Many Requests"));

        EventExplanation saved = explain(service(true), sixFreshReadings()).orElseThrow();

        assertThat(saved.getStatus()).isEqualTo(ExplanationStatus.FAILED);
        assertThat(saved.getError()).isEqualTo("Groq call failed: 429 Too Many Requests");
        assertThat(saved.getAssessment()).isNull();
        assertThat(saved.getExplanation()).isNull();
        assertThat(saved.getDataQualityNote()).isNotBlank();
        assertThat(event.getStatus()).isEqualTo(EventStatus.NEW);
        verifyNoInteractions(events); // the event row is never touched
    }

    @Test
    void unexpectedRuntimeError_isAlsoCaught() {
        when(client.explain(any())).thenThrow(new IllegalStateException("boom"));

        EventExplanation saved = explain(service(true), sixFreshReadings()).orElseThrow();

        assertThat(saved.getStatus()).isEqualTo(ExplanationStatus.FAILED);
        assertThat(saved.getError()).isEqualTo("boom");
    }

    @Test
    void databaseFailureWhileStoring_isSwallowed() {
        when(client.explain(any())).thenReturn(ANSWER);
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));

        assertThat(explain(service(true), sixFreshReadings())).isEmpty();
        assertThat(event.getStatus()).isEqualTo(EventStatus.NEW);
    }

    @Test
    void disabled_makesNoAiCallAndStoresNothing() {
        assertThat(explain(service(false), sixFreshReadings())).isEmpty();

        verify(client, never()).explain(any());
        verify(repo, never()).save(any());
    }

    @Test
    void alreadyExplained_isNotExplainedTwice() {
        when(repo.existsByEventId(event.getId())).thenReturn(true);

        assertThat(explain(service(true), sixFreshReadings())).isEmpty();

        verify(client, never()).explain(any());
    }

    @Test
    void onlyTheMostRecentReadingsAreSent() {
        when(client.explain(any())).thenReturn(ANSWER);
        double[] twenty = new double[20];
        java.util.Arrays.fill(twenty, 30);

        explain(service(true), hourly(Instant.parse("2026-10-18T03:00:00Z"), twenty));

        ArgumentCaptor<ExplanationContext> sent = ArgumentCaptor.forClass(ExplanationContext.class);
        verify(client).explain(sent.capture());
        assertThat(sent.getValue().readings()).hasSize(12);
        assertThat(sent.getValue().readings().get(11).observedAt()).isEqualTo(Instant.parse("2026-10-18T03:00:00Z"));
    }

    @Test
    void nonFiniteReadings_neverReachTheAiOrThePrompt() {
        when(client.explain(any())).thenReturn(ANSWER);

        explain(service(true), hourly(Instant.parse("2026-10-18T03:00:00Z"), 18, Double.NaN, 25, 45, 55, 70));

        ArgumentCaptor<ExplanationContext> sent = ArgumentCaptor.forClass(ExplanationContext.class);
        verify(client).explain(sent.capture());
        assertThat(sent.getValue().readings()).hasSize(5);
        assertThat(ExplanationPrompt.facts(sent.getValue())).doesNotContain("NaN");
    }
}

package com.cs203.healthwatch.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.detection.ReplayResult.Outcome;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.explanation.EventExplanationService;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** CG-135 wiring: a NEW event triggers exactly one explanation, and a failed explanation never breaks detection. */
class DetectionOrchestratorExplanationTest {

    private static final BaselineSnapshot BASELINE = new BaselineSnapshot("central", "pm25", 18.0, 5.0);

    private EventRepository events;
    private BaselineRepository baselines;
    private ReadingRepository readings;
    private EventExplanationService explanations;
    private DetectionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        events = mock(EventRepository.class);
        baselines = mock(BaselineRepository.class);
        readings = mock(ReadingRepository.class);
        explanations = mock(EventExplanationService.class);

        DetectionProperties detection = new DetectionProperties("central", "pm25", "air_quality", 3.0, 3,
                Duration.ofMinutes(90), new DetectionProperties.Replay("central", "pm25",
                "2026-09-03T00:00:00Z", "2026-09-05T00:00:00Z"));
        orchestrator = new DetectionOrchestrator(events, readings, baselines, detection,
                Clock.systemUTC(), explanations);

        when(baselines.latestBaseline("central", "pm25", "air_quality")).thenReturn(Optional.of(BASELINE));
        when(events.save(any())).thenAnswer(inv -> {
            DetectedEvent e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
    }

    /** Hourly readings from 2026-09-04T00:00Z: quiet, then a sustained spike starting at hour 4. */
    private static List<ReadingSnapshot> hazeEpisode() {
        double[] values = {18, 19, 17, 20, 45, 55, 70, 72};
        List<ReadingSnapshot> readings = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T00:00:00Z");
        for (int i = 0; i < values.length; i++) {
            readings.add(new ReadingSnapshot("central", "pm25", values[i], start.plus(Duration.ofHours(i))));
        }
        return readings;
    }

    private static Reading toReading(ReadingSnapshot snapshot) {
        Reading r = new Reading();
        r.setRegion(snapshot.region());
        r.setValue(snapshot.value());
        r.setObservedAt(snapshot.observedAt().atOffset(ZoneOffset.UTC));
        return r;
    }

    private void replayWindowReturns(List<ReadingSnapshot> snapshots) {
        when(this.readings.findValidBetween(eq("central"), eq("air_quality"), any(), any()))
                .thenReturn(snapshots.stream().map(DetectionOrchestratorExplanationTest::toReading).toList());
    }

    @SuppressWarnings("unchecked")
    @Test
    void liveEvent_isExplainedOnce_withTheNewestReadingsLast_andNaNIsNotCounted() {
        // 24 hourly readings, oldest -> newest, breach (45, 55, 70) at the end; reading #5 has no value (NaN)
        Instant newest = Instant.parse("2026-10-20T12:00:00Z");
        List<ReadingSnapshot> oldestFirst = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double value = i == 21 ? 45 : i == 22 ? 55 : i == 23 ? 70 : i == 5 ? Double.NaN : 18;
            oldestFirst.add(new ReadingSnapshot("central", "pm25", value, newest.minus(Duration.ofHours(23 - i))));
        }
        List<Reading> newestFirst = new ArrayList<>(oldestFirst.stream()
                .map(DetectionOrchestratorExplanationTest::toReading).toList());
        java.util.Collections.reverse(newestFirst); // the repository query returns newest first
        when(readings.findLatestValid(eq("central"), eq("air_quality"), eq(false), any())).thenReturn(newestFirst);

        Optional<DetectedEvent> created = orchestrator.runLiveDetection();

        assertThat(created).isPresent();
        assertThat(created.get().getConfidenceReadingCount()).isEqualTo(23); // 24 minus the NaN reading
        ArgumentCaptor<List<ReadingSnapshot>> sent = ArgumentCaptor.forClass(List.class);
        verify(explanations, times(1)).explainNewEvent(any(DetectedEvent.class), eq(BASELINE), sent.capture(),
                eq(3.0), eq(3));
        List<ReadingSnapshot> passed = sent.getValue();
        assertThat(passed).hasSize(24);
        assertThat(passed.get(23).observedAt()).isEqualTo(newest); // newest is the tail, as the service expects
        assertThat(passed.subList(21, 24)).extracting(ReadingSnapshot::value).containsExactly(45.0, 55.0, 70.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void replayEvent_isExplainedOnce_withReadingsUpToTheDetectionPoint() {
        replayWindowReturns(hazeEpisode());

        ReplayResult result = orchestrator.replayDemoWindow();

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        ArgumentCaptor<List<ReadingSnapshot>> sent = ArgumentCaptor.forClass(List.class);
        verify(explanations).explainNewEvent(any(DetectedEvent.class), eq(BASELINE), sent.capture(), eq(3.0), eq(3));
        // detection fires on the 3rd breaching reading (06:00); later readings must not leak into the explanation
        assertThat(sent.getValue()).hasSize(7);
        assertThat(sent.getValue().get(6).observedAt()).isEqualTo(Instant.parse("2026-09-04T06:00:00Z"));
    }

    @Test
    void explanationCrash_doesNotBreakDetection_andEventStaysNew() {
        replayWindowReturns(hazeEpisode());
        when(explanations.explainNewEvent(any(), any(), anyList(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("unexpected"));

        ReplayResult result = orchestrator.replayDemoWindow();

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.event().getStatus()).isEqualTo(EventStatus.NEW);
    }

    @Test
    void noEventCreated_meansNoExplanation() {
        when(events.findFirstByRegionAndSignalTypeAndReplayAndStatusIn(anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(Optional.of(new DetectedEvent())); // one already open
        replayWindowReturns(hazeEpisode());

        ReplayResult result = orchestrator.replayDemoWindow();

        assertThat(result.outcome()).isEqualTo(Outcome.ALREADY_OPEN);
        verify(explanations, never()).explainNewEvent(any(), any(), anyList(), anyDouble(), anyInt());
    }
}

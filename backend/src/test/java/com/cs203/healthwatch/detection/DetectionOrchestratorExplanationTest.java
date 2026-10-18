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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.detection.ReplayResult.Outcome;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.explanation.EventExplanationService;
import com.cs203.healthwatch.explanation.ExplanationProperties;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import java.time.Duration;
import java.time.Instant;
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
    private BaselineDevRepository baselineDev;
    private EventExplanationService explanations;
    private DetectionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        events = mock(EventRepository.class);
        baselineDev = mock(BaselineDevRepository.class);
        explanations = mock(EventExplanationService.class);

        DetectionProperties detection = new DetectionProperties("central", "pm25", "air_quality", 3.0, 3,
                Duration.ofMinutes(90), new DetectionProperties.Replay("central", "pm25",
                "2026-09-03T00:00:00Z", "2026-09-05T00:00:00Z"));
        ExplanationProperties explanation = new ExplanationProperties(true, "key", "m", "http://x",
                Duration.ofSeconds(5), Duration.ofHours(6), Duration.ofMinutes(60), Duration.ofHours(2), 12);
        orchestrator = new DetectionOrchestrator(events, mock(ReadingRepository.class), baselineDev, detection,
                explanations, explanation);

        when(baselineDev.latestBaseline("central", "pm25")).thenReturn(Optional.of(BASELINE));
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

    private void replayWindowReturns(List<ReadingSnapshot> readings) {
        when(baselineDev.readingsBetween(eq("central"), eq("pm25"), any(), any())).thenReturn(readings);
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

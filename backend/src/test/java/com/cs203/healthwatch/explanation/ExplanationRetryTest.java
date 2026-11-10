package com.cs203.healthwatch.explanation;

import static com.cs203.healthwatch.explanation.TestExplanations.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.detection.BaselineRepository;
import com.cs203.healthwatch.detection.BaselineSnapshot;
import com.cs203.healthwatch.detection.DetectionProperties;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * CG-152: retrying FAILED explanations. Real repositories on in-memory H2 (so the retry query is really exercised);
 * the AI client, readings and baselines are mocked, so CI never calls a live API.
 */
@DataJpaTest
@ActiveProfiles("test")
class ExplanationRetryTest {

    private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");
    private static final Instant DETECTED = Instant.parse("2026-10-18T03:00:00Z");
    private static final BaselineSnapshot BASELINE = new BaselineSnapshot("central", "pm25", 18.0, 5.0);
    private static final AiExplanation ANSWER = new AiExplanation(Assessment.GENUINE_TREND,
            "Consistent with regional haze", "PM2.5 rose steadily.", List.of("45, 55, 70 vs median 18"),
            List.of("Station reports correctly"));
    private static final DetectionProperties DETECTION = new DetectionProperties("central", "pm25", "air_quality",
            3.0, 3, Duration.ofMinutes(90), null);

    @Autowired TestEntityManager em;
    @Autowired EventExplanationRepository explanations;
    @Autowired EventRepository events;

    private ExplanationClient client;
    private ReadingRepository readingRepo;
    private BaselineRepository baselineRepo;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        client = mock(ExplanationClient.class);
        readingRepo = mock(ReadingRepository.class);
        baselineRepo = mock(BaselineRepository.class);
        clock = new MutableClock(NOW);
        when(client.modelName()).thenReturn("groq-test-model");
        when(baselineRepo.latestBaseline(anyString(), anyString(), anyString())).thenReturn(Optional.of(BASELINE));
        when(readingRepo.findValidUpTo(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(sixReadingsNewestFirst());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private EventExplanationService service(ExplanationProperties props) {
        return new EventExplanationService(client, explanations, events, props, readingRepo, baselineRepo,
                DETECTION, clock);
    }

    private ExplanationRetryJob job(ExplanationProperties props) {
        return new ExplanationRetryJob(explanations, service(props), props, clock);
    }

    private ExplanationRetryJob job() {
        return job(props(true, "key"));
    }

    /** Six hourly readings ending at DETECTED, newest first (the order the query returns them). */
    private static List<Reading> sixReadingsNewestFirst() {
        double[] values = {70, 55, 45, 25, 20, 18};
        List<Reading> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Reading r = new Reading();
            r.setRegion("central");
            r.setValue(values[i]);
            r.setObservedAt(DETECTED.minus(Duration.ofHours(i)).atOffset(ZoneOffset.UTC));
            list.add(r);
        }
        return list;
    }

    private DetectedEvent event(EventStatus status, String detectedAt) {
        DetectedEvent e = TestEvents.event(6.1, status, detectedAt);
        e.setRegion("central");
        e.setSignalType("pm25");
        return em.persistAndFlush(e);
    }

    private EventExplanation failedRow(DetectedEvent event, int attempts) {
        EventExplanation row = new EventExplanation();
        row.setEventId(event.getId());
        row.setStatus(ExplanationStatus.FAILED);
        row.setError("Groq call failed: 429 Too Many Requests");
        row.setDataComplete(true);
        row.setDataQualityNote("Based on complete data: 6 of 6 expected readings in the last 6 hours.");
        row.setAttempts(attempts);
        return em.persistAndFlush(row);
    }

    private EventExplanation okRow(DetectedEvent event) {
        EventExplanation row = failedRow(event, 1);
        row.setStatus(ExplanationStatus.OK);
        row.setAssessment(Assessment.UNCERTAIN);
        row.setLikelyCause("c");
        row.setExplanation("e");
        row.setEvidence(List.of("x"));
        row.setAssumptions(List.of("y"));
        row.setError(null);
        return em.persistAndFlush(row);
    }

    private EventExplanation reload(UUID eventId) {
        em.flush(); // saves on managed rows are only written on flush; clear() alone would throw them away
        em.clear();
        return explanations.findByEventId(eventId).orElseThrow();
    }

    // ---- the retry behaviour ------------------------------------------------------------------------------------

    @Test
    void failedThenRetriedToOk_updatesTheSameRow_exactlyOneRowRemains() {
        DetectedEvent event = event(EventStatus.NEW, "2026-10-18T03:00:00Z");
        EventExplanation failed = failedRow(event, 1);
        UUID rowId = failed.getId();
        when(client.explain(any())).thenReturn(ANSWER);

        int tried = job().retryBatch();

        assertThat(tried).isEqualTo(1);
        assertThat(explanations.count()).isEqualTo(1);
        EventExplanation row = reload(event.getId());
        assertThat(row.getId()).isEqualTo(rowId);
        assertThat(row.getStatus()).isEqualTo(ExplanationStatus.OK);
        assertThat(row.getAssessment()).isEqualTo(Assessment.GENUINE_TREND);
        assertThat(row.getEvidence()).containsExactly("45, 55, 70 vs median 18");
        assertThat(row.getError()).isNull();
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getModel()).isEqualTo("groq-test-model");
    }

    @Test
    void okRows_areNeverRerun() {
        DetectedEvent event = event(EventStatus.NEW, "2026-10-18T03:00:00Z");
        okRow(event);

        int tried = job().retryBatch();

        assertThat(tried).isZero();
        verifyNoInteractions(client);
        assertThat(reload(event.getId()).getAttempts()).isEqualTo(1);
    }

    @Test
    void rowAtMaxAttempts_isSkipped_andOneBelowMaxIsStillTried() {
        DetectedEvent exhausted = event(EventStatus.NEW, "2026-10-18T01:00:00Z");
        failedRow(exhausted, 3); // max is 3
        DetectedEvent lastChance = event(EventStatus.NEW, "2026-10-18T02:00:00Z");
        failedRow(lastChance, 2);
        when(client.explain(any())).thenReturn(ANSWER);

        int tried = job().retryBatch();

        assertThat(tried).isEqualTo(1);
        verify(client, times(1)).explain(any());
        assertThat(reload(exhausted.getId()).getStatus()).isEqualTo(ExplanationStatus.FAILED);
        assertThat(reload(exhausted.getId()).getAttempts()).isEqualTo(3);
        assertThat(reload(lastChance.getId()).getStatus()).isEqualTo(ExplanationStatus.OK);
    }

    @Test
    void eventNoLongerNew_isSkipped() {
        for (EventStatus status : List.of(EventStatus.UNDER_REVIEW, EventStatus.CONFIRMED, EventStatus.DISMISSED)) {
            failedRow(event(status, "2026-10-18T03:00:00Z"), 1);
        }

        int tried = job().retryBatch();

        assertThat(tried).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void failedRetry_incrementsAttempts_keepsEventNew_andDoesNotThrow() {
        DetectedEvent event = event(EventStatus.NEW, "2026-10-18T03:00:00Z");
        failedRow(event, 1);
        when(client.explain(any())).thenThrow(new ExplanationException("Groq call failed: timeout"));

        ExplanationRetryJob job = job();
        int tried = job.retryBatch();
        job.run(); // the scheduled entry point must not throw either

        assertThat(tried).isEqualTo(1);
        EventExplanation row = reload(event.getId());
        assertThat(row.getStatus()).isEqualTo(ExplanationStatus.FAILED);
        assertThat(row.getError()).isEqualTo("Groq call failed: timeout");
        assertThat(row.getAttempts()).isEqualTo(3); // retryBatch + run()
        assertThat(explanations.count()).isEqualTo(1);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.NEW);

        // at max attempts it stops: one more run makes no AI call
        job.run();
        verify(client, times(2)).explain(any());
    }

    @Test
    void batchSizeIsRespected_andOldestEventGoesFirst() {
        DetectedEvent newest = event(EventStatus.NEW, "2026-10-18T05:00:00Z");
        DetectedEvent oldest = event(EventStatus.NEW, "2026-10-18T01:00:00Z");
        DetectedEvent third = event(EventStatus.NEW, "2026-10-18T04:00:00Z");
        DetectedEvent second = event(EventStatus.NEW, "2026-10-18T02:00:00Z");
        for (DetectedEvent e : List.of(newest, oldest, third, second)) {
            failedRow(e, 1);
        }
        when(client.explain(any())).thenReturn(ANSWER);
        ExplanationProperties twoPerRun = props(true, "key",
                new ExplanationProperties.Retry(Duration.ofMinutes(5), 2, 3));

        int tried = job(twoPerRun).retryBatch();

        assertThat(tried).isEqualTo(2);
        ArgumentCaptor<ExplanationContext> sent = ArgumentCaptor.forClass(ExplanationContext.class);
        verify(client, times(2)).explain(sent.capture());
        assertThat(sent.getAllValues()).extracting(ExplanationContext::eventId)
                .containsExactly(oldest.getId(), second.getId());
        assertThat(reload(third.getId()).getAttempts()).isEqualTo(1); // untouched
        assertThat(reload(newest.getId()).getAttempts()).isEqualTo(1);
    }

    @Test
    void disabledFeature_makesNoCalls() {
        failedRow(event(EventStatus.NEW, "2026-10-18T03:00:00Z"), 1);

        assertThat(job(props(false, "key")).retryBatch()).isZero();

        verifyNoInteractions(client);
    }

    @Test
    void blankApiKey_makesNoCalls() {
        failedRow(event(EventStatus.NEW, "2026-10-18T03:00:00Z"), 1);

        assertThat(job(props(true, "")).retryBatch()).isZero();
        assertThat(job(props(true, "   ")).retryBatch()).isZero();

        verifyNoInteractions(client);
    }

    @Test
    void contextThatCannotBeRebuilt_countsAsAFailedTry_andDoesNotBlockTheQueue() {
        DetectedEvent broken = event(EventStatus.NEW, "2026-10-18T01:00:00Z");
        failedRow(broken, 1);
        DetectedEvent fine = event(EventStatus.NEW, "2026-10-18T02:00:00Z");
        failedRow(fine, 1);
        when(baselineRepo.latestBaseline(anyString(), anyString(), anyString())).thenReturn(Optional.empty())
                .thenReturn(Optional.of(BASELINE));
        when(client.explain(any())).thenReturn(ANSWER);

        int tried = job().retryBatch();

        assertThat(tried).isEqualTo(2);
        EventExplanation brokenRow = reload(broken.getId());
        assertThat(brokenRow.getAttempts()).isEqualTo(2);
        assertThat(brokenRow.getStatus()).isEqualTo(ExplanationStatus.FAILED);
        assertThat(brokenRow.getError()).contains("no usable baseline");
        assertThat(reload(fine.getId()).getStatus()).isEqualTo(ExplanationStatus.OK);
        verify(client, times(1)).explain(any()); // no AI call for the broken one
    }

    // ---- rate limiting ------------------------------------------------------------------------------------------

    @Test
    void rateLimit_isStoredWithThePrefix_stopsTheRun_andPausesForRetryAfter() {
        DetectedEvent first = event(EventStatus.NEW, "2026-10-18T01:00:00Z");
        DetectedEvent second = event(EventStatus.NEW, "2026-10-18T02:00:00Z");
        failedRow(first, 1);
        failedRow(second, 1);
        when(client.explain(any())).thenThrow(new RateLimitedException("Groq call failed: 429 Too Many Requests",
                Duration.ofSeconds(120), null));
        ExplanationRetryJob job = job();

        assertThat(job.retryBatch()).isEqualTo(1); // stopped after the first 429, second row not tried
        EventExplanation row = reload(first.getId());
        assertThat(row.getError()).startsWith("RATE_LIMITED:");
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(reload(second.getId()).getAttempts()).isEqualTo(1);
        verify(client, times(1)).explain(any());

        clock.advance(Duration.ofSeconds(60)); // still inside Retry-After: nothing is called
        assertThat(job.retryBatch()).isZero();
        verify(client, times(1)).explain(any());

        clock.advance(Duration.ofSeconds(61)); // past it: retries resume
        assertThat(job.retryBatch()).isGreaterThan(0);
        verify(client, times(2)).explain(any());
    }

    // ---- the context a retry rebuilds ---------------------------------------------------------------------------

    @Test
    void replayEventContext_matchesALiveEventsContext() {
        DetectedEvent live = event(EventStatus.NEW, "2026-10-18T03:00:00Z");
        DetectedEvent replay = TestEvents.event(6.1, EventStatus.NEW, "2026-10-18T03:00:00Z");
        replay.setRegion("central");
        replay.setSignalType("pm25");
        replay.setReplay(true);
        replay = em.persistAndFlush(replay);
        EventExplanationService service = service(props(true, "key"));

        ExplanationContext liveContext = service.buildContext(live);
        ExplanationContext replayContext = service.buildContext(replay);

        assertThat(replayContext.replay()).isTrue();
        assertThat(liveContext.replay()).isFalse();
        assertThat(replayContext).usingRecursiveComparison()
                .ignoringFields("eventId", "replay")
                .isEqualTo(liveContext);
        // oldest first, ending at the detection time, judged fresh at that time (not at "now", 3h later)
        assertThat(liveContext.readings()).extracting(r -> r.value()).containsExactly(18.0, 20.0, 25.0, 45.0, 55.0, 70.0);
        assertThat(liveContext.readings().get(5).observedAt()).isEqualTo(DETECTED);
        assertThat(liveContext.dataQuality().complete()).isTrue();
        assertThat(liveContext.baselineMedian()).isEqualTo(18.0);
        // live events never see synthetic readings; replay events may
        verify(readingRepo).findValidUpTo(eq("central"), eq("air_quality"), eq(false), eq(DETECTED.atOffset(ZoneOffset.UTC)), any());
        verify(readingRepo).findValidUpTo(eq("central"), eq("air_quality"), eq(true), eq(DETECTED.atOffset(ZoneOffset.UTC)), any());
        verify(readingRepo, never()).findLatestValid(any(), any(), anyBoolean(), any());
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }
}

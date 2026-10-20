package com.cs203.healthwatch.detection;

import com.cs203.healthwatch.confidence.ConfidenceInputs;
import com.cs203.healthwatch.confidence.ConfidenceResult;
import com.cs203.healthwatch.confidence.ConfidenceScorer;
import com.cs203.healthwatch.detection.ReplayResult.Outcome;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionOrchestrator {

    private static final List<EventStatus> OPEN_STATUSES =
            List.of(EventStatus.NEW, EventStatus.UNDER_REVIEW);

    private final EventRepository eventRepository;
    private final ReadingRepository readingRepository;
    private final BaselineRepository baselineRepository;
    private final DetectionProperties config;
    private final Clock clock;

    /**
     * Live detection, run after every ingestion pull: the latest real readings in public.readings, compared with
     * the baseline computed in baseline_dev (Demo 1; see BaselineDevRepository). Events are labelled with the
     * signal (pm25), not the feed's source type.
     *
     * @return the event created, or empty if none was created
     */
    public Optional<DetectedEvent> runLiveDetection() {
        String region = config.region();
        String signalType = config.signalType();

        Optional<BaselineSnapshot> baseline = usableBaseline(region, signalType);
        if (baseline.isEmpty()) {
            return Optional.empty();
        }

        // query returns newest first; the detector expects oldest -> newest
        // load a full confidence window of readings; the detector itself only looks at the last N
        int limit = Math.max(config.consecutiveReadings(), ConfidenceScorer.EXPECTED_READINGS);
        List<ReadingSnapshot> recent = new ArrayList<>(
                loadRecentLiveReadings(region, config.sourceType(), signalType, limit));
        Collections.reverse(recent);

        Instant now = clock.instant();
        return DeviationDetector.detect(
                        baseline, recent, config.zThreshold(), config.consecutiveReadings(), config.maxGap())
                .flatMap(d -> saveIfNoneOpen(d, false, now, countInWindow(recent, d.timestamp())));
    }

    /**
     * Demo replay (S1-6): walks the configured historical window of public.readings through the same detector,
     * reading by reading as if it were arriving live, and records the first sustained breach as a replay event.
     */
    public ReplayResult replayDemoWindow() {
        DetectionProperties.Replay window = config.replay();
        Instant from = Instant.parse(window.from());
        Instant to = Instant.parse(window.to());

        Optional<BaselineSnapshot> baseline = usableBaseline(window.region(), window.signalType());
        if (baseline.isEmpty()) {
            return new ReplayResult(Outcome.NO_BASELINE, "No usable baseline in public.baselines for "
                    + window.region() + "/" + window.signalType(), 0, null, null);
        }

        List<ReadingSnapshot> readings = readingRepository
                .findValidBetween(window.region(), config.sourceType(), from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC))
                .stream().map(r -> new ReadingSnapshot(r.getRegion(), window.signalType(),r.getValue(), r.getObservedAt().toInstant()))
                .toList();
        if (readings.isEmpty()) {
            return new ReplayResult(Outcome.NO_READINGS, "No valid readings in public.readings between "
                    + from + " and " + to, 0, baseline.get(), null);
        }

        Optional<Deviation> deviation = DeviationDetector.firstDetection(
                baseline, readings, config.zThreshold(), config.consecutiveReadings(), config.maxGap());
        if (deviation.isEmpty()) {
            return new ReplayResult(Outcome.NO_ANOMALY, "No sustained deviation in the window",
                    readings.size(), baseline.get(), null);
        }

        // replay is scored as of the detection moment, as if it had just happened (staleness 0)
        Deviation found = deviation.get();
        int inWindow = countInWindow(readings, found.timestamp());
        return saveIfNoneOpen(found, true, found.timestamp(), inWindow)
                .map(event -> new ReplayResult(Outcome.CREATED, "Replay event created",
                        readings.size(), baseline.get(), event))
                .orElseGet(() -> new ReplayResult(Outcome.ALREADY_OPEN,
                        "A replay event is already open for this region and signal; dismiss it to replay again",
                        readings.size(), baseline.get(), null));
    }

    private Optional<BaselineSnapshot> usableBaseline(String region, String signalType) {
        Optional<BaselineSnapshot> baseline = baselineRepository.latestBaseline(region, signalType, config.sourceType());
        if (baseline.isEmpty()) {
            log.info("No baseline for {}/{} — skipping detection", region, signalType);
            return Optional.empty();
        }
        if (!baseline.get().hasUsableSpread()) {
            log.warn("Baseline for {}/{} has zero or non-finite spread (CG-137 guard) — skipping detection",
                    region, signalType);
            return Optional.empty();
        }
        return baseline;
    }

    /** Valid readings in the confidence window ending at {@code end} (inclusive); the window is 24h. */
    private static int countInWindow(List<ReadingSnapshot> readings, Instant end) {
        Instant start = end.minus(ConfidenceScorer.WINDOW);
        return (int) readings.stream()
                .filter(r -> r != null && Double.isFinite(r.value()))
                .filter(r -> r.observedAt().isAfter(start) && !r.observedAt().isAfter(end))
                .count();
    }

    /**
     * Saves a NEW event unless one of the same kind (live or replay) is already open for this region and signal.
     * The confidence score is computed here by ConfidenceScorer from measured inputs and stored with the event.
     *
     * @param scoredAt       the moment staleness is measured against (now for live; detection time for replay)
     * @param readingCount   valid readings in the 24h window before the event
     */
    private Optional<DetectedEvent> saveIfNoneOpen(Deviation d, boolean replay, Instant scoredAt, int readingCount) {
        boolean alreadyOpen = eventRepository
                .findFirstByRegionAndSignalTypeAndReplayAndStatusIn(d.region(), d.signalType(), replay, OPEN_STATUSES)
                .isPresent();
        if (alreadyOpen) {
            log.info("{} event already open for {}/{} — not creating a duplicate",
                    replay ? "Replay" : "Live", d.region(), d.signalType());
            return Optional.empty();
        }

        DetectedEvent event = new DetectedEvent();
        event.setRegion(d.region());
        event.setSignalType(d.signalType());
        event.setDetectedAt(d.timestamp());
        event.setDeviation(d.deviationSize());
        event.setStatus(EventStatus.NEW);
        event.setReplay(replay);

        // source agreement is null until a second source exists (CG-136); the scorer leaves it out
        ConfidenceInputs inputs = new ConfidenceInputs(
                Math.max(0, Duration.between(d.timestamp(), scoredAt).toSeconds()),
                readingCount, d.deviationSize(), null);
        ConfidenceResult confidence = ConfidenceScorer.score(inputs);
        event.applyConfidence(inputs, confidence);

        DetectedEvent saved = eventRepository.save(event);

        log.info("Created NEW {} event for {}/{} — deviation={} confidence={} ({})",
                replay ? "replay" : "live", d.region(), d.signalType(), d.deviationSize(),
                confidence.score(), confidence.level());
        return Optional.of(saved);
    }

    /**
     * Latest valid real (non-synthetic) readings from public.readings, newest first. public.readings has no
     * signal_type column yet, so readings are found by their data source's type and labelled with the signal.
     */
    private List<ReadingSnapshot> loadRecentLiveReadings(String region, String sourceType, String signalType,
                                                         int limit) {
        return readingRepository
                .findLatestValid(region, sourceType, false, PageRequest.of(0, limit))
                .stream()
                .map(r -> new ReadingSnapshot(
                        r.getRegion(),
                        signalType,
                        r.getValue() == null ? Double.NaN : r.getValue(), // detector rejects NaN
                        r.getObservedAt().toInstant()))
                .toList();
    }
}

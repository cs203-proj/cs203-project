package com.cs203.healthwatch.detection;

import com.cs203.healthwatch.detection.ReplayResult.Outcome;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.explanation.EventExplanationService;
import com.cs203.healthwatch.explanation.ExplanationProperties;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
    private final BaselineDevRepository baselineDevRepository;
    private final DetectionProperties config;
    private final EventExplanationService explanationService;
    private final ExplanationProperties explanationConfig;

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
        List<ReadingSnapshot> recent = new ArrayList<>(
                loadRecentLiveReadings(region, config.sourceType(), signalType, config.consecutiveReadings()));
        Collections.reverse(recent);

        Optional<DetectedEvent> created = DeviationDetector.detect(
                        baseline, recent, config.zThreshold(), config.consecutiveReadings(), config.maxGap())
                .flatMap(d -> saveIfNoneOpen(d, false));

        // CG-135: explain the new event. The event is already saved, so nothing here can undo or block it.
        created.ifPresent(event -> explain(event, baseline.get(), () -> {
            List<ReadingSnapshot> evidence = new ArrayList<>(loadRecentLiveReadings(
                    region, config.sourceType(), signalType, explanationConfig.maxEvidenceReadings()));
            Collections.reverse(evidence);
            return evidence;
        }));
        return created;
    }

    /**
     * Demo replay (S1-6): walks the configured historical window of baseline_dev.readings through the same detector,
     * reading by reading as if it were arriving live, and records the first sustained breach as a replay event.
     */
    public ReplayResult replayDemoWindow() {
        DetectionProperties.Replay window = config.replay();
        Instant from = Instant.parse(window.from());
        Instant to = Instant.parse(window.to());

        Optional<BaselineSnapshot> baseline = usableBaseline(window.region(), window.signalType());
        if (baseline.isEmpty()) {
            return new ReplayResult(Outcome.NO_BASELINE, "No usable baseline in baseline_dev.baselines for "
                    + window.region() + "/" + window.signalType(), 0, null, null);
        }

        List<ReadingSnapshot> readings =
                baselineDevRepository.readingsBetween(window.region(), window.signalType(), from, to);
        if (readings.isEmpty()) {
            return new ReplayResult(Outcome.NO_READINGS, "No valid readings in baseline_dev.readings between "
                    + from + " and " + to, 0, baseline.get(), null);
        }

        Optional<Deviation> deviation = DeviationDetector.firstDetection(
                baseline, readings, config.zThreshold(), config.consecutiveReadings(), config.maxGap());
        if (deviation.isEmpty()) {
            return new ReplayResult(Outcome.NO_ANOMALY, "No sustained deviation in the window",
                    readings.size(), baseline.get(), null);
        }

        return saveIfNoneOpen(deviation.get(), true)
                .map(event -> {
                    // CG-135: explain using the replay readings up to the detection point, as a live run would have
                    Instant until = event.getDetectedAt().plusSeconds(1);
                    explain(event, baseline.get(), () -> readings.stream()
                            .filter(r -> r.observedAt().isBefore(until))
                            .toList());
                    return event;
                })
                .map(event -> new ReplayResult(Outcome.CREATED, "Replay event created",
                        readings.size(), baseline.get(), event))
                .orElseGet(() -> new ReplayResult(Outcome.ALREADY_OPEN,
                        "A replay event is already open for this region and signal; dismiss it to replay again",
                        readings.size(), baseline.get(), null));
    }

    /** Runs the AI explanation for a new event. Any failure is logged and swallowed: detection must carry on. */
    private void explain(DetectedEvent event, BaselineSnapshot baseline, Supplier<List<ReadingSnapshot>> readings) {
        try {
            explanationService.explainNewEvent(event, baseline, readings.get(), config.zThreshold(),
                    config.consecutiveReadings());
        } catch (Exception e) {
            log.error("Explanation step failed for event {}; event stays NEW: {}", event.getId(), e.getMessage(), e);
        }
    }

    private Optional<BaselineSnapshot> usableBaseline(String region, String signalType) {
        Optional<BaselineSnapshot> baseline = baselineDevRepository.latestBaseline(region, signalType);
        if (baseline.isEmpty()) {
            log.info("No baseline for {}/{} — skipping detection", region, signalType);
            return Optional.empty();
        }
        if (baseline.get().scaledMad() <= 0) {
            log.warn("Baseline for {}/{} has zero spread (flat history) — skipping detection", region, signalType);
            return Optional.empty();
        }
        return baseline;
    }

    /** Saves a NEW event unless one of the same kind (live or replay) is already open for this region and signal. */
    private Optional<DetectedEvent> saveIfNoneOpen(Deviation d, boolean replay) {
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
        DetectedEvent saved = eventRepository.save(event);

        log.info("Created NEW {} event for {}/{} — deviation={}",
                replay ? "replay" : "live", d.region(), d.signalType(), d.deviationSize());
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

package com.cs203.healthwatch.detection;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionOrchestrator {

    private static final List<EventStatus> OPEN_STATUSES =
            List.of(EventStatus.NEW, EventStatus.UNDER_REVIEW);

    private final EventRepository eventRepository;
    private final DetectionProperties config;
    // inject once CG-9 merges:
    // private final ReadingRepository readingRepository;
    // baseline comes from the Python service over HTTP (see loadBaseline TODO), not a JPA repo

    /**
     * Runs detection for one region and signal.
     *
     * @param replay true when called from the replay endpoint; stored on the event so
     *               replays are never mistaken for live detections
     * @return the event created, or empty if none was created
     */
    public Optional<DetectedEvent> runDetection(String region, String signalType, boolean replay) {
        Optional<BaselineSnapshot> baseline = loadBaseline(region, signalType);

        if (baseline.isEmpty()) {
            log.info("No baseline for {}/{} — skipping detection", region, signalType);
            return Optional.empty();
        }
        if (baseline.get().scaledMad() <= 0) {
            log.warn("Baseline for {}/{} has zero spread (flat history) — skipping detection", region, signalType);
            return Optional.empty();
        }

        // query returns newest first; the detector expects oldest -> newest
        List<ReadingSnapshot> recent = new ArrayList<>(
                loadRecentReadings(region, signalType, config.consecutiveReadings()));
        Collections.reverse(recent);

        Optional<Deviation> deviation = DeviationDetector.detect(
                baseline, recent, config.zThreshold(), config.consecutiveReadings(), config.maxGap());

        if (deviation.isEmpty()) {
            return Optional.empty();
        }

        // Dedupe only against events of the same kind, so an open replay event
        // never blocks a live one (and vice versa)
        boolean alreadyOpen = eventRepository
                .findFirstByRegionAndSignalTypeAndReplayAndStatusIn(region, signalType, replay, OPEN_STATUSES)
                .isPresent();
        if (alreadyOpen) {
            log.info("{} event already open for {}/{} — not creating a duplicate",
                    replay ? "Replay" : "Live", region, signalType);
            return Optional.empty();
        }

        Deviation d = deviation.get();
        DetectedEvent event = new DetectedEvent();
        event.setRegion(d.region());
        event.setSignalType(d.signalType());
        event.setDetectedAt(d.timestamp());
        event.setDeviation(d.deviationSize());
        event.setStatus(EventStatus.NEW);
        event.setReplay(replay);
        DetectedEvent saved = eventRepository.save(event);

        log.info("Created NEW {} event for {}/{} — deviation={}",
                replay ? "replay" : "live", region, signalType, d.deviationSize());
        return Optional.of(saved);
    }

    private Optional<BaselineSnapshot> loadBaseline(String region, String signalType) {
        // TODO: fetch baseline from the Python baseline service (ai/baseline/)
        //   - Call GET /baselines over HTTP (not a JPA repo)
        //   - Send X-Admin-Token header, read the value from an env var
        //   - z = (value - median) / mad  (mad is already scaled by 1.4826, don't rescale)
        // Blocked on:
        //   - branch cg-62-baseline-oracle being merged
        //   - schema decision: public vs baseline_dev (CG-2)
        throw new UnsupportedOperationException("wire this to S1-5/CG-62's baseline HTTP API — see TODO above");
    }

    private List<ReadingSnapshot> loadRecentReadings(String region, String signalType, int limit) {
        // TODO: fetch recent readings via CG-9's ReadingRepository
        //   - Add a query for the latest N non-malformed readings in a region, newest first
        //   - Filter by signal type: Reading has no signalType, so likely
        //     go via sourceId -> DataSource (confirm with CG-9)
        // Blocked on:
        //   - branch CG-9-source_ingestion being merged
        // To raise with CG-9:
        //   - ReadingRepository uses UUID as the ID type but Reading.id is Long
        throw new UnsupportedOperationException("wire this to CG-9's ReadingRepository — see TODO above");
    }
}

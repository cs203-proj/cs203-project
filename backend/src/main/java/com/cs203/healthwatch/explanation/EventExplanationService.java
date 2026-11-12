package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.detection.BaselineRepository;
import com.cs203.healthwatch.detection.BaselineSnapshot;
import com.cs203.healthwatch.detection.DetectionProperties;
import com.cs203.healthwatch.detection.ReadingSnapshot;
import com.cs203.healthwatch.detection.RegionReadings;
import com.cs203.healthwatch.detection.ScopeResult;
import com.cs203.healthwatch.detection.SpatialScopeCalculator;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties;
import com.cs203.healthwatch.ingestion.readings.Reading;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explains each newly detected event with AI (CG-135) and stores the result. Never changes the event and never
 * throws: if anything fails, the event stays NEW, the error is logged, and a FAILED row records why.
 * CG-152: a FAILED row can be tried again ({@link #retry}); the retry updates that row, never adds a second one.
 */
@Slf4j
@Service
public class EventExplanationService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final ExplanationClient client;
    private final EventExplanationRepository explanations;
    private final EventRepository events;
    private final ExplanationProperties props;
    private final ReadingRepository readings;    // only needed by buildContext (retries)
    private final BaselineRepository baselines;   // only needed by buildContext (retries)
    private final DetectionProperties detection;  // only needed by buildContext (retries)
    private final PmSourceProperties pm;        // only needed by buildContext and the scope (known regions)
    private final Clock clock;

    /**
     * The result of one AI try.
     *
     * @param row         the stored row (OK or FAILED)
     * @param rateLimited true if the provider answered 429
     * @param retryAfter  the provider's Retry-After, or null if it sent none
     */
    record Attempt(EventExplanation row, boolean rateLimited, Duration retryAfter) {}

    @Autowired
    public EventExplanationService(ExplanationClient client, EventExplanationRepository explanations,
                                   EventRepository events, ExplanationProperties props,
                                   ReadingRepository readings, BaselineRepository baselines,
                                   DetectionProperties detection, PmSourceProperties pm) {
        this(client, explanations, events, props, readings, baselines, detection, pm, Clock.systemUTC());
    }

    /** Live path only (no retries): what the CG-135 tests use. */
    EventExplanationService(ExplanationClient client, EventExplanationRepository explanations,
                            EventRepository events, ExplanationProperties props, Clock clock) {
        this(client, explanations, events, props, null, null, null, null, clock);
    }

    /** Retry tests: no known regions, so the scope is always UNKNOWN. */
    EventExplanationService(ExplanationClient client, EventExplanationRepository explanations,
                            EventRepository events, ExplanationProperties props,
                            ReadingRepository readings, BaselineRepository baselines,
                            DetectionProperties detection, Clock clock) {
        this(client, explanations, events, props, readings, baselines, detection, null, clock);
    }

    EventExplanationService(ExplanationClient client, EventExplanationRepository explanations,
                            EventRepository events, ExplanationProperties props,
                            ReadingRepository readings, BaselineRepository baselines,
                            DetectionProperties detection, PmSourceProperties pm, Clock clock) {
        this.client = client;
        this.explanations = explanations;
        this.events = events;
        this.props = props;
        this.readings = readings;
        this.baselines = baselines;
        this.detection = detection;
        this.pm = pm;
        this.clock = clock;
    }

    /**
     * Called by detection right after it records a NEW event.
     *
     * @param readings recent valid readings for the event's region and signal, oldest first
     * @return the stored explanation (OK or FAILED), or empty if disabled, already explained, or it could not be
     *         stored
     */
    public Optional<EventExplanation> explainNewEvent(DetectedEvent event, BaselineSnapshot baseline,
                                                      List<ReadingSnapshot> readings, double zThreshold,
                                                      int consecutiveReadings) {
        try {
            if (!props.enabled()) {
                log.debug("AI explanations disabled; skipping event {}", event.getId());
                return Optional.empty();
            }
            if (explanations.existsByEventId(event.getId())) {
                return Optional.empty();
            }

            // Live: judge freshness against now. Replay: against the detection time, since the data is historical.
            Instant reference = event.isReplay() ? event.getDetectedAt() : clock.instant();
            ExplanationContext context = assembleContext(event, baseline, readings, zThreshold,
                    consecutiveReadings, reference);

            return Optional.of(callAndSave(event, context, null).row());
        } catch (Exception e) {
            log.error("Could not explain or store explanation for event {}: {}", event.getId(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Rebuilds what detection knew when it raised {@code event} (CG-152): the baseline and the newest valid readings
     * up to the detection time. Live and replay events go through the same steps. Freshness is judged at the
     * detection time for both, because by the time of a retry every reading would look stale against "now".
     * The baseline is the latest one stored (earlier baselines are not kept).
     *
     * @throws ExplanationException if there is no usable baseline or the retry dependencies are missing
     */
    ExplanationContext buildContext(DetectedEvent event) {
        if (readings == null || baselines == null || detection == null) {
            throw new ExplanationException("Cannot rebuild the context: readings and baselines are not available");
        }
        BaselineSnapshot baseline = baselines
                .latestBaseline(event.getRegion(), event.getSignalType(), detection.sourceType())
                .filter(BaselineSnapshot::hasUsableSpread)
                .orElseThrow(() -> new ExplanationException("Cannot rebuild the context: no usable baseline for "
                        + event.getRegion() + "/" + event.getSignalType()));

        List<ReadingSnapshot> recent = new ArrayList<>(readings
                .findValidUpTo(event.getRegion(), detection.sourceType(), event.isReplay(),
                        event.getDetectedAt().atOffset(ZoneOffset.UTC), PageRequest.of(0, props.maxEvidenceReadings()))
                .stream()
                .map(r -> new ReadingSnapshot(r.getRegion(), event.getSignalType(), r.getValue(),
                        r.getObservedAt().toInstant()))
                .toList());
        Collections.reverse(recent); // the query is newest first; the prompt wants oldest first

        return assembleContext(event, baseline, recent, detection.zThreshold(), detection.consecutiveReadings(),
                event.getDetectedAt());
    }

    /**
     * Asks the AI and stores the answer. With {@code existing == null} a new row is saved (attempts = 1); otherwise
     * that row is updated in place and its attempts go up by one. AI failures become a FAILED row; only a failure to
     * store the row is thrown.
     */
    Attempt callAndSave(DetectedEvent event, ExplanationContext context, EventExplanation existing) {
        EventExplanation row = existing != null ? existing : new EventExplanation();
        if (existing == null) {
            row.setEventId(event.getId());
        } else {
            row.setAttempts(existing.getAttempts() + 1);
        }
        row.setDataComplete(context.dataQuality().complete());
        row.setDataQualityNote(context.dataQuality().note());
        row.setModel(client.modelName());
        // computed in code, so it is stored even when the AI call fails
        row.setScope(context.scope().scope());
        row.setRegionsAbove(context.scope().regionsAbove());
        row.setRegionsCompared(context.scope().regionsCompared());

        boolean rateLimited = false;
        Duration retryAfter = null;
        try {
            AiExplanation ai = client.explain(context);
            row.setStatus(ExplanationStatus.OK);
            row.setAssessment(ai.assessment());
            row.setLikelyCause(ai.likelyCause());
            row.setExplanation(ai.explanation());
            row.setEvidence(ai.evidence());
            row.setAssumptions(ai.assumptions());
            row.setError(null);
        } catch (Exception e) {
            log.error("AI explanation failed for event {} ({}/{}), attempt {}; event stays NEW: {}",
                    event.getId(), event.getRegion(), event.getSignalType(), row.getAttempts(), e.getMessage(), e);
            row.setStatus(ExplanationStatus.FAILED);
            row.setError(truncate(e.getMessage()));
            if (e instanceof RateLimitedException limited) {
                rateLimited = true;
                retryAfter = limited.retryAfter();
            }
        }

        EventExplanation saved = explanations.save(row);
        log.info("Stored {} explanation for event {} (attempt {})", saved.getStatus(), event.getId(),
                saved.getAttempts());
        return new Attempt(saved, rateLimited, retryAfter);
    }

    /**
     * One retry of a FAILED explanation (CG-152). Skips (empty, no AI call, no attempt counted) if the event is gone
     * or no longer NEW. If the context cannot be rebuilt, that counts as a failed try so one broken row can never
     * block the rows behind it. Never throws.
     */
    Optional<Attempt> retry(EventExplanation failed) {
        try {
            Optional<DetectedEvent> found = events.findById(failed.getEventId());
            if (found.isEmpty() || found.get().getStatus() != EventStatus.NEW) {
                log.info("Not retrying explanation for event {}: event is gone or no longer NEW", failed.getEventId());
                return Optional.empty();
            }
            DetectedEvent event = found.get();

            ExplanationContext context;
            try {
                context = buildContext(event);
            } catch (Exception e) {
                log.warn("Could not rebuild the context for event {}: {}", event.getId(), e.getMessage());
                failed.setAttempts(failed.getAttempts() + 1);
                failed.setError(truncate(e.getMessage()));
                return Optional.of(new Attempt(explanations.save(failed), false, null));
            }
            return Optional.of(callAndSave(event, context, failed));
        } catch (Exception e) {
            log.error("Retry of the explanation for event {} failed: {}", failed.getEventId(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private ExplanationContext assembleContext(DetectedEvent event, BaselineSnapshot baseline,
                                               List<ReadingSnapshot> readings, double zThreshold,
                                               int consecutiveReadings, Instant reference) {
        List<ReadingSnapshot> evidence = lastN(readings, props.maxEvidenceReadings());
        DataQuality quality = DataQuality.assess(evidence, reference, props.lookback(),
                props.expectedInterval(), props.staleAfter());
        return new ExplanationContext(event.getId(), event.getRegion(),
                event.getSignalType(), event.getDetectedAt(),
                event.getDeviation() == null ? Double.NaN : event.getDeviation(), event.isReplay(),
                baseline.median(), baseline.scaledMad(), zThreshold, consecutiveReadings, evidence, quality,
                scopeFor(event, baseline, readings, zThreshold), knownRegions());
    }

    private List<String> knownRegions() {
        return pm == null ? List.of() : pm.regions().stream().map(PmSourceProperties.Region::name).sorted().toList();
    }

    /**
     * Local spike or island-wide (CG-169). Readings: each region's newest valid one in (detectedAt - maxGap,
     * detectedAt], picked like DetectionOrchestrator.sourceAgreement does; the flagged region's own come from
     * {@code flaggedReadings}. Baselines: BaselineRepository.latestBaseline, the source detection uses. For a replay
     * that is the CURRENT baseline, not the one at event time. Never throws: any failure gives UNKNOWN, so the
     * explanation is still written.
     */
    private ScopeResult scopeFor(DetectedEvent event, BaselineSnapshot baseline, List<ReadingSnapshot> flaggedReadings,
                                 double zThreshold) {
        try {
            if (readings == null || baselines == null || detection == null || pm == null) {
                return ScopeResult.unknown("the cross-region data was not available");
            }
            Instant end = event.getDetectedAt();
            Set<String> known = Set.copyOf(knownRegions());
            List<ReadingSnapshot> all = new ArrayList<>(flaggedReadings);
            for (Reading r : readings.findOtherRegionsValidBetween(event.getRegion(), detection.sourceType(),
                    event.isReplay(), end.minus(detection.maxGap()).atOffset(ZoneOffset.UTC),
                    end.atOffset(ZoneOffset.UTC))) {
                all.add(new ReadingSnapshot(r.getRegion(), event.getSignalType(), r.getValue(),
                        r.getObservedAt().toInstant()));
            }
            Map<String, ReadingSnapshot> atSameTime = new HashMap<>();
            for (ReadingSnapshot r : RegionReadings.newestPerRegionWithin(all, end, detection.maxGap())) {
                atSameTime.put(r.region(), r);
            }
            Map<String, BaselineSnapshot> regionBaselines = new HashMap<>();
            regionBaselines.put(event.getRegion(), baseline);
            for (String region : atSameTime.keySet()) {
                if (!regionBaselines.containsKey(region)) {
                    baselines.latestBaseline(region, event.getSignalType(), detection.sourceType())
                            .ifPresent(b -> regionBaselines.put(region, b));
                }
            }
            return SpatialScopeCalculator.computeScope(event.getRegion(), atSameTime, regionBaselines, known,
                    zThreshold);
        } catch (Exception e) {
            log.warn("Could not compute the spatial scope for event {}: {}", event.getId(), e.getMessage(), e);
            return ScopeResult.unknown("the comparison could not be computed");
        }
    }

    /** For GET /events/{id}/explanation. */
    @Transactional(readOnly = true)
    public EventExplanation getForEvent(UUID eventId) {
        if (!events.existsById(eventId)) {
            throw new ResourceNotFoundException("Event " + eventId + " not found");
        }
        return explanations.findByEventId(eventId).orElseThrow(() ->
                new ResourceNotFoundException("Event " + eventId + " has no explanation yet"));
    }

    private static List<ReadingSnapshot> lastN(List<ReadingSnapshot> readings, int n) {
        if (readings == null) {
            return List.of();
        }
        // readings are oldest first, so the newest n are the tail; non-finite values (null in the DB) never reach the prompt
        List<ReadingSnapshot> finite = readings.stream().filter(r -> Double.isFinite(r.value())).toList();
        return finite.size() <= n ? finite : finite.subList(finite.size() - n, finite.size());
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}

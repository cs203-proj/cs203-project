package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.detection.BaselineSnapshot;
import com.cs203.healthwatch.detection.ReadingSnapshot;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explains each newly detected event with AI (CG-135) and stores the result. Never changes the event and never
 * throws: if anything fails, the event stays NEW, the error is logged, and a FAILED row records why.
 */
@Slf4j
@Service
public class EventExplanationService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final ExplanationClient client;
    private final EventExplanationRepository explanations;
    private final EventRepository events;
    private final ExplanationProperties props;
    private final Clock clock;

    @Autowired
    public EventExplanationService(ExplanationClient client, EventExplanationRepository explanations,
                                   EventRepository events, ExplanationProperties props) {
        this(client, explanations, events, props, Clock.systemUTC());
    }

    EventExplanationService(ExplanationClient client, EventExplanationRepository explanations,
                            EventRepository events, ExplanationProperties props, Clock clock) {
        this.client = client;
        this.explanations = explanations;
        this.events = events;
        this.props = props;
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
            List<ReadingSnapshot> evidence = lastN(readings, props.maxEvidenceReadings());
            DataQuality quality = DataQuality.assess(evidence, reference, props.lookback(),
                    props.expectedInterval(), props.staleAfter());

            ExplanationContext context = new ExplanationContext(event.getId(), event.getRegion(),
                    event.getSignalType(), event.getDetectedAt(),
                    event.getDeviation() == null ? Double.NaN : event.getDeviation(), event.isReplay(),
                    baseline.median(), baseline.scaledMad(), zThreshold, consecutiveReadings, evidence, quality);

            EventExplanation row = new EventExplanation();
            row.setEventId(event.getId());
            row.setDataComplete(quality.complete());
            row.setDataQualityNote(quality.note());
            row.setModel(client.modelName());

            try {
                AiExplanation ai = client.explain(context);
                row.setStatus(ExplanationStatus.OK);
                row.setAssessment(ai.assessment());
                row.setLikelyCause(ai.likelyCause());
                row.setExplanation(ai.explanation());
                row.setEvidence(ai.evidence());
                row.setAssumptions(ai.assumptions());
            } catch (Exception e) {
                log.error("AI explanation failed for event {} ({}/{}); event stays NEW: {}",
                        event.getId(), event.getRegion(), event.getSignalType(), e.getMessage(), e);
                row.setStatus(ExplanationStatus.FAILED);
                row.setError(truncate(e.getMessage()));
            }

            EventExplanation saved = explanations.save(row);
            log.info("Stored {} explanation for event {}", saved.getStatus(), event.getId());
            return Optional.of(saved);
        } catch (Exception e) {
            log.error("Could not explain or store explanation for event {}: {}", event.getId(), e.getMessage(), e);
            return Optional.empty();
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
        return readings.size() <= n ? readings : readings.subList(readings.size() - n, readings.size());
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}

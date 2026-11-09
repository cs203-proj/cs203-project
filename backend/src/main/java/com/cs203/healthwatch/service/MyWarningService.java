package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.dto.MyWarningListResponse;
import com.cs203.healthwatch.dto.MyWarningResponse;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.explanation.EventExplanation;
import com.cs203.healthwatch.explanation.EventExplanationRepository;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.profile.Audience;
import com.cs203.healthwatch.profile.HealthCondition;
import com.cs203.healthwatch.profile.Region;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningFeedProperties;
import com.cs203.healthwatch.warnings.WarningRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of warnings for the signed-in end user (CG-22 feed, CG-23 confidence and "why this applies").
 *
 * <p>Only APPROVED warnings are ever returned (the repository query filters on status), matched on the user's region
 * and audience. Everything about the user that appears in a response comes from that user's own profile.
 */
@Service
public class MyWarningService {

    static final String INCOMPLETE_PROFILE_MESSAGE =
            "Complete your profile (region and audience) to see warnings that apply to you.";

    private final UserRepository userRepository;
    private final WarningRepository warningRepository;
    private final EventRepository eventRepository;
    private final EventExplanationRepository explanationRepository;
    private final WarningFeedProperties properties;
    private final Clock clock;

    public MyWarningService(UserRepository userRepository, WarningRepository warningRepository,
                            EventRepository eventRepository, EventExplanationRepository explanationRepository,
                            WarningFeedProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.warningRepository = warningRepository;
        this.eventRepository = eventRepository;
        this.explanationRepository = explanationRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MyWarningListResponse forUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Region region = Region.fromCode(user.getRegion()).orElse(null);
        Audience audience = Audience.fromCode(user.getAudience()).orElse(null);
        if (region == null || audience == null) {
            return new MyWarningListResponse(List.of(), INCOMPLETE_PROFILE_MESSAGE);
        }

        Instant since = clock.instant().minus(properties.activeWindow());
        List<Warning> warnings = warningRepository.findActiveFor(region.code(), audience.code(), since);
        if (warnings.isEmpty()) {
            return new MyWarningListResponse(List.of(), null);
        }

        Set<UUID> eventIds = warnings.stream().map(Warning::getEventId).collect(Collectors.toSet());
        Map<UUID, DetectedEvent> events = eventRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(DetectedEvent::getId, Function.identity()));
        Map<UUID, EventExplanation> explanations = explanationRepository.findByEventIdIn(eventIds).stream()
                .collect(Collectors.toMap(EventExplanation::getEventId, Function.identity()));

        String why = whyThisApplies(region, audience, user.getHealthConditions());
        List<MyWarningResponse> items = warnings.stream()
                .map(w -> toResponse(w, events.get(w.getEventId()), explanations.get(w.getEventId()), why))
                .toList();
        return new MyWarningListResponse(items, null);
    }

    private static MyWarningResponse toResponse(Warning w, DetectedEvent event, EventExplanation explanation,
                                                String why) {
        ConfidenceLevel level = event.getConfidenceLevel();
        MyWarningResponse.Confidence confidence = new MyWarningResponse.Confidence(level, meaning(level));

        MyWarningResponse.AiLine aiLine = (w.getAiLine() == null || w.getAiLine().isBlank())
                ? null : new MyWarningResponse.AiLine(w.getAiLine(), "AI-generated");

        // The note is only passed on when the system itself judged the data stale or partial (computed in code, CG-135).
        String note = (explanation != null && !explanation.isDataComplete()) ? explanation.getDataQualityNote() : null;

        return new MyWarningResponse(w.getId(), w.getEventId(), w.getSeverity(), event.getRegion(), w.getAudience(),
                w.displayText(), w.getApprovedAt(), confidence, why, aiLine, note);
    }

    /** Plain-language meaning of each band; the bands themselves come from ConfidenceScorer (CG-16). */
    static String meaning(ConfidenceLevel level) {
        if (level == null) {
            return "Confidence has not been assessed for this warning yet.";
        }
        return switch (level) {
            case HIGH -> "High confidence: the readings are fresh, plentiful and clearly above normal. "
                    + "Act on the advice.";
            case MEDIUM -> "Moderate confidence: the signal looks real, but some checks are weaker. "
                    + "Treat it as a likely warning and check for updates.";
            case LOW -> "Low confidence: the signal is weak or the data is thin. "
                    + "Keep the advice in mind, but this may turn out to be a false alarm.";
        };
    }

    /**
     * Deterministic reason line built only from the user's own profile. Health conditions are named only for the
     * vulnerable audience and only the ones this user entered.
     */
    static String whyThisApplies(Region region, Audience audience, Collection<String> conditionCodes) {
        String line = "You are in the " + region.code() + " region and receive advice for "
                + audienceLabel(audience) + ".";
        if (audience == Audience.VULNERABLE) {
            List<String> names = conditionCodes.stream()
                    .map(HealthCondition::fromCode)
                    .flatMap(java.util.Optional::stream)
                    .sorted()
                    .map(c -> c.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                    .toList();
            if (!names.isEmpty()) {
                line += " Your profile lists " + String.join(", ", names) + ".";
            }
        }
        return line;
    }

    private static String audienceLabel(Audience audience) {
        return switch (audience) {
            case GENERAL_PUBLIC -> "the general public";
            case VULNERABLE -> "vulnerable groups";
            case HEALTHCARE_WORKER -> "healthcare workers";
        };
    }
}

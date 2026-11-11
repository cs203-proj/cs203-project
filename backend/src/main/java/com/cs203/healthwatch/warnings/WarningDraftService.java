package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.profile.Audience;
import com.cs203.healthwatch.service.AuditService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drafts warnings when an event is confirmed: one per audience (general public CG-21, vulnerable groups and healthcare
 * workers CG-154). Runs inside the caller's transaction, so a confirmed event, its drafts and their audit entries
 * (CG-36) are kept or rolled back together. Drafts start as PENDING_REVIEW; nothing is sent to anyone.
 *
 * <p>The optional AI line is not written here. A {@link WarningsDrafted} event is published instead, and
 * {@link AiLineService} handles it only after this transaction commits, so a slow or failing AI call can never hold
 * up or roll back the confirm.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarningDraftService {

    /** Audit action for a new draft; the actor is always 'system'. */
    public static final String DRAFT_CREATED = "DRAFT_CREATED";

    private final WarningRepository warningRepository;
    private final WarningDrafter drafter;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    /**
     * @return the new drafts, general public first. Empty if the event is not confirmed. An audience that already has
     *         a draft for this event is skipped, so calling this twice never duplicates a draft.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Warning> draftFor(DetectedEvent event) {
        if (event.getStatus() != EventStatus.CONFIRMED) {
            return List.of();
        }

        List<Warning> drafted = new ArrayList<>();
        for (Audience audience : Audience.values()) {
            if (warningRepository.existsByEventIdAndAudience(event.getId(), audience.code())) {
                log.info("Event {} already has a {} warning draft - not drafting another", event.getId(), audience);
                continue;
            }
            drafted.add(draft(event, audience));
        }

        if (!drafted.isEmpty()) {
            events.publishEvent(new WarningsDrafted(drafted.stream().map(Warning::getId).toList()));
        }
        return drafted;
    }

    private Warning draft(DetectedEvent event, Audience audience) {
        WarningDrafter.Draft draft = drafter.draft(event, event.getConfidenceLevel(), audience);

        Warning warning = new Warning();
        warning.setEventId(event.getId());
        warning.setAudience(draft.audience());
        warning.setSeverity(draft.severity());
        warning.setStatus(WarningStatus.PENDING_REVIEW);
        warning.setWarningText(draft.text());
        Warning saved = warningRepository.save(warning);

        auditService.recordBySystem(saved.getId(), DRAFT_CREATED, null, Map.of(
                "eventId", event.getId(),
                "audience", saved.getAudience(),
                "severity", saved.getSeverity(),
                "status", saved.getStatus(),
                "warningText", saved.getWarningText()));
        log.info("Drafted {} {} warning {} for event {}", draft.severity(), audience, saved.getId(), event.getId());
        return saved;
    }
}

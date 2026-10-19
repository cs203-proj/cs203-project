package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.service.AuditService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drafts a warning when an event is confirmed (CG-21). Runs inside the caller's transaction, so a confirmed event, its
 * draft and the draft's audit entry (CG-36) are kept or rolled back together. Drafts start as PENDING_REVIEW; nothing is sent to anyone.
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

    /** @return the new draft, or empty if the event is not confirmed or already has a general-public draft */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Warning> draftFor(DetectedEvent event) {
        if (event.getStatus() != EventStatus.CONFIRMED) {
            return Optional.empty();
        }
        if (warningRepository.existsByEventIdAndAudience(event.getId(), WarningDrafter.GENERAL_PUBLIC)) {
            log.info("Event {} already has a general-public warning draft — not drafting another", event.getId());
            return Optional.empty();
        }

        // TODO(CG-16): pass the event's confidence level once it is stored with the event
        WarningDrafter.Draft draft = drafter.draft(event, null);

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
        log.info("Drafted {} warning {} for event {}", draft.severity(), saved.getId(), event.getId());
        return Optional.of(saved);
    }
}

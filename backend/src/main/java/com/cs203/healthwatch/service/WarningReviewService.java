package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.WarningResponse;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side of the admin review queue (CG-28): approve, edit, downgrade or suppress a drafted warning before
 * anything is sent. {@link WarningService} stays read-only.
 *
 * <p>Two rules hold for every action here:
 *
 * <ul>
 *   <li>Only a PENDING_REVIEW warning can be reviewed. Once a warning is APPROVED or SUPPRESSED the decision is
 *       made, and re-opening it would mean a warning could change after an end user had already seen it.
 *   <li>Each action writes its own audit entry (CG-36) inside the same transaction as the change. The audit call
 *       is deliberately not wrapped in a try/catch: if the audit write fails the whole action rolls back, so a
 *       change to a public health warning can never exist without a record of who made it.
 * </ul>
 *
 * <p>Nothing here sends anything to end users. Delivery is Sprint 3.
 */
@Service
public class WarningReviewService {

    /** Audit action names. These are read by the audit trail endpoint, so they are part of the API. */
    public static final String APPROVED = "APPROVED";
    public static final String EDITED = "EDITED";
    public static final String DOWNGRADED = "DOWNGRADED";
    public static final String SUPPRESSED = "SUPPRESSED";

    private final WarningRepository warningRepository;
    private final AuditService auditService;

    public WarningReviewService(WarningRepository warningRepository, AuditService auditService) {
        this.warningRepository = warningRepository;
        this.auditService = auditService;
    }

    /**
     * Approves the warning so the end-user feed (CG-22) can pick it up. Stamps approvedAt, which is what the feed
     * uses to decide whether a warning is still active.
     *
     * @param actorId the authenticated admin, taken from the token and never from the request body
     */
    @Transactional
    public WarningResponse approve(UUID warningId, UUID actorId) {
        Warning warning = pendingWarning(warningId);
        Map<String, Object> before = snapshot(warning);

        warning.setStatus(WarningStatus.APPROVED);
        warning.setApprovedAt(Instant.now());
        Warning saved = warningRepository.saveAndFlush(warning);

        auditService.recordByUser(warningId, actorId, APPROVED, before, snapshot(saved));
        return WarningResponse.from(saved);
    }

    /**
     * Replaces the wording an end user will see. The AI's original draft is untouched — V7 makes warning_text
     * immutable in the database — so the edit lands in editedText and both stay visible side by side.
     *
     * <p>The warning stays PENDING_REVIEW: editing is not the same as approving, and an edited warning still has to
     * be approved before it can go anywhere.
     */
    @Transactional
    public WarningResponse edit(UUID warningId, String newText, UUID actorId) {
        Warning warning = pendingWarning(warningId);
        Map<String, Object> before = snapshot(warning);

        warning.setEditedText(newText);
        Warning saved = warningRepository.saveAndFlush(warning);

        auditService.recordByUser(warningId, actorId, EDITED, before, snapshot(saved));
        return WarningResponse.from(saved);
    }

    /**
     * Lowers the severity. The target must be strictly less severe than the current value, so "downgrade" can never
     * quietly escalate a warning. The warning stays PENDING_REVIEW.
     *
     * @param reason why the severity is being lowered; recorded in the audit trail
     */
    @Transactional
    public WarningResponse downgrade(UUID warningId, String requestedSeverity, String reason, UUID actorId) {
        WarningSeverity target = WarningSeverity.parse(requestedSeverity);
        Warning warning = pendingWarning(warningId);
        WarningSeverity current = warning.getSeverity();

        if (!target.isLessSevereThan(current)) {
            throw new BadRequestException("Cannot downgrade from " + current + " to " + target
                    + ". A downgrade has to be less severe than the current severity.");
        }

        Map<String, Object> before = snapshot(warning);
        warning.setSeverity(target);
        Warning saved = warningRepository.saveAndFlush(warning);

        auditService.recordByUser(warningId, actorId, DOWNGRADED, before, withReason(snapshot(saved), reason));
        return WarningResponse.from(saved);
    }

    /**
     * Suppresses the warning so it is never sent. This is final: a suppressed warning is no longer PENDING_REVIEW,
     * so it cannot be approved afterwards.
     *
     * @param reason why the public is deliberately not being told; recorded in the audit trail
     */
    @Transactional
    public WarningResponse suppress(UUID warningId, String reason, UUID actorId) {
        Warning warning = pendingWarning(warningId);
        Map<String, Object> before = snapshot(warning);

        warning.setStatus(WarningStatus.SUPPRESSED);
        Warning saved = warningRepository.saveAndFlush(warning);

        auditService.recordByUser(warningId, actorId, SUPPRESSED, before, withReason(snapshot(saved), reason));
        return WarningResponse.from(saved);
    }

    /**
     * Loads the warning for update and rejects it unless it is still awaiting review. The row stays locked until the
     * transaction ends, so two admins acting on the same warning queue up rather than racing.
     */
    private Warning pendingWarning(UUID warningId) {
        Warning warning = warningRepository.findByIdForUpdate(warningId)
                .orElseThrow(() -> new ResourceNotFoundException("Warning " + warningId + " not found"));

        if (warning.getStatus() != WarningStatus.PENDING_REVIEW) {
            throw new BadRequestException("Warning is " + warning.getStatus()
                    + ". Only a PENDING_REVIEW warning can be approved, edited, downgraded or suppressed.");
        }
        return warning;
    }

    /**
     * State of the warning for the audit log's before/after snapshot. A LinkedHashMap rather than Map.of: editedText
     * and approvedAt are null until someone edits or approves, and Map.of rejects null values.
     */
    private static Map<String, Object> snapshot(Warning warning) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", warning.getStatus());
        state.put("severity", warning.getSeverity());
        state.put("warningText", warning.getWarningText());
        state.put("editedText", warning.getEditedText());
        state.put("approvedAt", warning.getApprovedAt());
        return state;
    }

    /** The reason belongs to the action, not to the warning, so it rides along in the audit snapshot. */
    private static Map<String, Object> withReason(Map<String, Object> state, String reason) {
        state.put("reason", reason);
        return state;
    }
}

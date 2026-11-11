package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.explanation.EventExplanation;
import com.cs203.healthwatch.explanation.EventExplanationRepository;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import com.cs203.healthwatch.profile.Audience;
import com.cs203.healthwatch.service.AuditService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adds the optional AI-written "why this matters" line ({@code warnings.ai_line}) to each audience's draft (CG-154),
 * built from the event's CG-135 explanation (assessment and likely cause) plus the audience. The AI never writes or
 * changes the recommended action, and is never sent it.
 *
 * <p>If the explanation is missing or FAILED, no AI call is made and the draft stays without a line.
 *
 * <p>Runs in the background after the confirm has committed, so the admin never waits on the AI and an AI failure
 * never undoes a confirm. Every failure is logged and leaves {@code ai_line} null; the warning itself is unaffected.
 * The line is only written while the warning is still PENDING_REVIEW, so an admin always sees it before approval.
 */
@Slf4j
@Service
public class AiLineService {

    /** Audit action when an AI line is added; the actor is always 'system'. */
    public static final String AI_LINE_ADDED = "AI_LINE_ADDED";

    private final AiLineClient client;
    private final AiLineProperties props;
    private final WarningRepository warnings;
    private final EventRepository events;
    private final EventExplanationRepository explanations;
    private final AuditService auditService;
    private final TransactionTemplate tx;

    public AiLineService(AiLineClient client, AiLineProperties props, WarningRepository warnings,
                         EventRepository events, EventExplanationRepository explanations, AuditService auditService,
                         TransactionTemplate tx) {
        this.client = client;
        this.props = props;
        this.warnings = warnings;
        this.events = events;
        this.explanations = explanations;
        this.auditService = auditService;
        this.tx = tx;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWarningsDrafted(WarningsDrafted drafted) {
        if (!props.enabled() || !client.isConfigured()) {
            log.debug("AI line disabled or not configured; skipping {} drafts", drafted.warningIds().size());
            return;
        }
        drafted.warningIds().forEach(this::addLine);
    }

    /**
     * Writes the AI line for one warning if it should have one. Never throws.
     *
     * @return the stored line, or empty if none was written
     */
    public Optional<String> addLine(UUID warningId) {
        try {
            Warning warning = warnings.findById(warningId).orElse(null);
            if (warning == null || !needsLine(warning)) {
                return Optional.empty();
            }
            DetectedEvent event = events.findById(warning.getEventId()).orElse(null);
            if (event == null) {
                return Optional.empty();
            }
            EventExplanation explanation = explanations.findByEventId(event.getId()).orElse(null);
            if (explanation == null || explanation.getStatus() != ExplanationStatus.OK
                    || explanation.getAssessment() == null || explanation.getLikelyCause() == null) {
                log.warn("No usable AI explanation for event {} (missing or FAILED); warning {} gets no AI line",
                        event.getId(), warningId);
                return Optional.empty();
            }

            Audience audience = Audience.fromCode(warning.getAudience()).orElseThrow();
            AiLineContext context = new AiLineContext(audience, event.getRegion(), event.getSignalType(),
                    warning.getSeverity(), event.getConfidenceLevel(), explanation.getAssessment(),
                    explanation.getLikelyCause());

            String line = clean(client.write(context), props.maxLength());
            if (line == null) {
                log.warn("AI line for warning {} was empty or too long; leaving it out", warningId);
                return Optional.empty();
            }

            Boolean stored = tx.execute(status -> {
                if (warnings.setAiLineIfPending(warningId, line, Instant.now()) == 0) {
                    return false; // approved, suppressed or given a line in the meantime
                }
                auditService.recordBySystem(warningId, AI_LINE_ADDED, null, Map.of("aiLine", line));
                return true;
            });
            if (Boolean.TRUE.equals(stored)) {
                log.info("Added AI line to {} warning {}", audience, warningId);
                return Optional.of(line);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Could not add AI line to warning {}; warning stays without one: {}",
                    warningId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static boolean needsLine(Warning warning) {
        return Audience.fromCode(warning.getAudience()).isPresent()
                && warning.getStatus() == WarningStatus.PENDING_REVIEW
                && (warning.getAiLine() == null || warning.getAiLine().isBlank());
    }

    /**
     * Collapses whitespace and strips wrapping quotes.
     *
     * @return the cleaned line, or null if it is blank or longer than {@code maxLength}
     */
    static String clean(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String line = raw.replaceAll("\\s+", " ").trim();
        if (line.length() >= 2 && (line.startsWith("\"") && line.endsWith("\""))) {
            line = line.substring(1, line.length() - 1).trim();
        }
        return (line.isEmpty() || line.length() > maxLength) ? null : line;
    }
}

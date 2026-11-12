package com.cs203.healthwatch.warnings;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WarningRepository extends JpaRepository<Warning, UUID> {
    // Review-queue order (CG-29), done in the database: most severe first, then oldest first (the warning that has
    // waited longest is handled next), then id so the order is total and pages never overlap. Severity is stored as
    // text, so it is ranked with CASE rather than sorted alphabetically. The Pageable must be unsorted.
    String QUEUE_ORDER = " order by case w.severity"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.CRITICAL then 0"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.HIGH then 1"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.MEDIUM then 2"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.LOW then 3"
            + " else -1 end, w.createdAt asc, w.id asc";

    // CG-165: the same queue with the event's AI explanation and confidence attached, in ONE query. Warning only
    // stores event_id (no @ManyToOne), so these are entity joins ("left join X on ..."). LEFT, so a warning whose
    // event has no explanation or no confidence is still listed. event_explanations.event_id and events.id are both
    // unique, so each warning matches at most one row and the paging and the count are unchanged.
    String QUEUE_ROWS = "select new com.cs203.healthwatch.warnings.WarningQueueRow("
            + "w, x.status, x.assessment, x.likelyCause, e.confidenceScore, e.confidenceLevel,"
            + " x.scope, x.regionsAbove, x.regionsCompared, x.scopeSummary)"
            + " from Warning w"
            + " left join com.cs203.healthwatch.events.DetectedEvent e on e.id = w.eventId"
            + " left join com.cs203.healthwatch.explanation.EventExplanation x on x.eventId = w.eventId";

    @Query(value = QUEUE_ROWS + QUEUE_ORDER, countQuery = "select count(w) from Warning w")
    Page<WarningQueueRow> findQueueRows(Pageable page);

    @Query(value = QUEUE_ROWS + " where w.status = :status" + QUEUE_ORDER,
            countQuery = "select count(w) from Warning w where w.status = :status")
    Page<WarningQueueRow> findQueueRows(WarningStatus status, Pageable page);

    @Query(value = "select w from Warning w" + QUEUE_ORDER,
            countQuery = "select count(w) from Warning w")
    Page<Warning> findQueue(Pageable page);

    @Query(value = "select w from Warning w where w.status = :status" + QUEUE_ORDER,
            countQuery = "select count(w) from Warning w where w.status = :status")
    Page<Warning> findQueue(WarningStatus status, Pageable page);

    /**
     * Loads the warning and locks its row until the transaction ends, so two admins reviewing the same warning at
     * once queue up instead of overwriting each other (CG-28). Mirrors EventRepository.findByIdForUpdate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warning w where w.id = :id")
    Optional<Warning> findByIdForUpdate(UUID id);

    boolean existsByEventIdAndAudience(UUID eventId, String audience);

    List<Warning> findByEventIdOrderByCreatedAtAscIdAsc(UUID eventId);

    /**
     * End-user feed (CG-22): APPROVED warnings for this audience whose event is in this region and that were approved
     * at or after {@code since}. Most severe first, then newest approval first, then id so the order is total.
     * Status is matched in the query itself, so pending and suppressed warnings can never be returned.
     */
    @Query("select w from Warning w, DetectedEvent e where e.id = w.eventId"
            + " and w.status = com.cs203.healthwatch.warnings.WarningStatus.APPROVED"
            + " and e.region = :region and w.audience = :audience and w.approvedAt >= :since"
            + " order by case w.severity"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.CRITICAL then 0"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.HIGH then 1"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.MEDIUM then 2"
            + " when com.cs203.healthwatch.warnings.WarningSeverity.LOW then 3"
            + " else 4 end, w.approvedAt desc, w.id asc")
    List<Warning> findActiveFor(String region, String audience, Instant since);

    /**
     * Sets the AI line (CG-154) only if the warning is still pending review and has none yet, so an AI line can never
     * appear on a warning an admin has already approved or suppressed.
     *
     * @return 1 if the line was set, 0 if not
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Warning w set w.aiLine = :line, w.updatedAt = :now where w.id = :id and w.aiLine is null"
            + " and w.status = com.cs203.healthwatch.warnings.WarningStatus.PENDING_REVIEW")
    int setAiLineIfPending(UUID id, String line, Instant now);
}

package com.cs203.healthwatch.warnings;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query(value = "select w from Warning w" + QUEUE_ORDER,
            countQuery = "select count(w) from Warning w")
    Page<Warning> findQueue(Pageable page);

    @Query(value = "select w from Warning w where w.status = :status" + QUEUE_ORDER,
            countQuery = "select count(w) from Warning w where w.status = :status")
    Page<Warning> findQueue(WarningStatus status, Pageable page);

    boolean existsByEventIdAndAudience(UUID eventId, String audience);

    List<Warning> findByEventIdOrderByCreatedAtAscIdAsc(UUID eventId);
}

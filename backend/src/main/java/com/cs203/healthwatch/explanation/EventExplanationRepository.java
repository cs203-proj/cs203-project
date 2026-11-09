package com.cs203.healthwatch.explanation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.cs203.healthwatch.events.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventExplanationRepository extends JpaRepository<EventExplanation, UUID> {

    Optional<EventExplanation> findByEventId(UUID eventId);

    List<EventExplanation> findByEventIdIn(Collection<UUID> eventIds);

    boolean existsByEventId(UUID eventId);

    /**
     * FAILED explanations worth another try (CG-152): the event is still {@code eventStatus} and fewer than
     * {@code maxAttempts} tries were made. Oldest event first, so a backlog drains in the order it was detected.
     * The Pageable must be unsorted (it only carries the batch size).
     */
    @Query("""
            select x from EventExplanation x, DetectedEvent e
            where e.id = x.eventId
              and x.status = :status
              and e.status = :eventStatus
              and x.attempts < :maxAttempts
            order by e.detectedAt asc, x.id asc
            """)
    List<EventExplanation> findRetryable(ExplanationStatus status, EventStatus eventStatus, int maxAttempts,
                                         Pageable page);
}

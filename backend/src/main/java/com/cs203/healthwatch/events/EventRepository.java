package com.cs203.healthwatch.events;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<DetectedEvent, UUID> {

    Optional<DetectedEvent> findFirstByRegionAndSignalTypeAndReplayAndStatusIn(
            String region, String signalType, boolean replay, Collection<EventStatus> statuses);

    // Sorted in the database: largest deviation first, then newest first, then id, so the order is total and
    // pages never overlap. "nulls last" guards rows created before deviation was always set. The Pageable must be
    // unsorted (it only carries paging).
    String LIST_ORDER = " order by e.deviation desc nulls last, e.detectedAt desc, e.id asc";

    @Query(value = "select e from DetectedEvent e" + LIST_ORDER,
            countQuery = "select count(e) from DetectedEvent e")
    Page<DetectedEvent> findAllForList(Pageable page);

    @Query(value = "select e from DetectedEvent e where e.status = :status" + LIST_ORDER,
            countQuery = "select count(e) from DetectedEvent e where e.status = :status")
    Page<DetectedEvent> findAllForList(EventStatus status, Pageable page);

    /** Loads the event and locks its row until the transaction ends, so concurrent status changes queue up. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DetectedEvent e where e.id = :id")
    Optional<DetectedEvent> findByIdForUpdate(UUID id);
}

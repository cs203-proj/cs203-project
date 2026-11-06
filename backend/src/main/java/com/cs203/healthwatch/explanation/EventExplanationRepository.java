package com.cs203.healthwatch.explanation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventExplanationRepository extends JpaRepository<EventExplanation, UUID> {

    Optional<EventExplanation> findByEventId(UUID eventId);

    boolean existsByEventId(UUID eventId);
}

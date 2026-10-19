package com.cs203.healthwatch.warnings;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarningRepository extends JpaRepository<Warning, UUID> {

    boolean existsByEventIdAndAudience(UUID eventId, String audience);

    List<Warning> findByEventIdOrderByCreatedAtAscIdAsc(UUID eventId);
}

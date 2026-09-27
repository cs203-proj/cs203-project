package com.cs203.healthwatch.events;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<DetectedEvent, UUID> {

    Optional<DetectedEvent> findFirstByRegionAndSignalTypeAndReplayAndStatusIn(
            String region, String signalType, boolean replay, Collection<EventStatus> statuses);
}

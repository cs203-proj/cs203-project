package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.AuditEntryResponse;
import com.cs203.healthwatch.dto.EventListResponse;
import com.cs203.healthwatch.dto.EventResponse;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.model.AuditLog;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.AuditLogRepository;
import com.cs203.healthwatch.repository.OffsetPageRequest;
import com.cs203.healthwatch.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final EventRepository eventRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public EventService(EventRepository eventRepository, AuditLogRepository auditLogRepository,
                        UserRepository userRepository) {
        this.eventRepository = eventRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /** @param status optional status filter (case-insensitive); null returns every status */
    @Transactional(readOnly = true)
    public EventListResponse list(String status, int limit, long offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new BadRequestException("offset must be 0 or greater");
        }
        EventStatus statusFilter = (status == null) ? null : EventStatus.parse(status);

        OffsetPageRequest page = new OffsetPageRequest(offset, limit);
        Page<DetectedEvent> result = (statusFilter == null)
                ? eventRepository.findAllForList(page)
                : eventRepository.findAllForList(statusFilter, page);

        List<EventResponse> items = result.getContent().stream().map(EventResponse::from).toList();
        return new EventListResponse(items, result.getTotalElements(), limit, offset);
    }

    /**
     * Moves an event to a new status and records the change in the audit log. Both writes share one transaction:
     * if either fails, neither is kept.
     *
     * @param actorId id of the authenticated user making the change (from the token, never the request body)
     */
    @Transactional
    public EventResponse changeStatus(UUID eventId, String requestedStatus, UUID actorId) {
        EventStatus target = EventStatus.parse(requestedStatus);
        DetectedEvent event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> eventNotFound(eventId));

        EventStatus current = event.getStatus();
        if (current == target) {
            throw new BadRequestException("Event is already " + current);
        }
        if (!current.canMoveTo(target)) {
            String allowed = current.allowedNext().isEmpty()
                    ? "none, " + current + " is final"
                    : current.allowedNext().stream().map(Enum::name).collect(Collectors.joining(", "));
            throw new BadRequestException(
                    "Cannot change status from " + current + " to " + target + ". Allowed from " + current + ": "
                            + allowed);
        }

        event.setStatus(target);
        eventRepository.saveAndFlush(event);
        // Must not be caught here: if this insert fails, the status update above has to roll back with it.
        auditLogRepository.save(new AuditLog(eventId, actorId, current, target, Instant.now()));
        return EventResponse.from(event);
    }

    /** Full change history of one event, oldest first. */
    @Transactional(readOnly = true)
    public List<AuditEntryResponse> auditTrail(UUID eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw eventNotFound(eventId);
        }
        List<AuditLog> entries = auditLogRepository.findByEventIdOrderByChangedAtAscIdAsc(eventId);

        List<UUID> actorIds = entries.stream().map(AuditLog::getActorId).distinct().toList();
        Map<UUID, String> usernames = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));

        return entries.stream()
                .map(e -> new AuditEntryResponse(e.getId(), e.getActorId(), usernames.get(e.getActorId()),
                        e.getOldStatus(), e.getNewStatus(), e.getChangedAt()))
                .toList();
    }

    private static ResourceNotFoundException eventNotFound(UUID id) {
        return new ResourceNotFoundException("Event " + id + " not found");
    }
}

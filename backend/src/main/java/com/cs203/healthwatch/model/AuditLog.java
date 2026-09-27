package com.cs203.healthwatch.model;

import com.cs203.healthwatch.events.EventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One status change on an event. Append-only: every column is non-updatable, Hibernate treats the entity as
 * read-only after insert, and a database trigger rejects UPDATE/DELETE (V3 migration).
 */
@Entity
@Immutable
@Table(name = "audit_log")
@Getter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false, nullable = false)
    private Long id;

    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "actor_id", updatable = false, nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", updatable = false)
    private EventStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", updatable = false, nullable = false)
    private EventStatus newStatus;

    @Column(name = "changed_at", updatable = false, nullable = false)
    private Instant changedAt;

    public AuditLog(UUID eventId, UUID actorId, EventStatus oldStatus, EventStatus newStatus, Instant changedAt) {
        this.eventId = eventId;
        this.actorId = actorId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedAt = changedAt;
    }
}

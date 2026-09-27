package com.cs203.healthwatch.repository;

import com.cs203.healthwatch.model.AuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Insert and read only. Deliberately extends {@link Repository}, not CrudRepository/JpaRepository, so no
 * update or delete method exists anywhere in the application.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog entry);

    /** Oldest first; id breaks ties between changes in the same instant. */
    List<AuditLog> findByEventIdOrderByChangedAtAscIdAsc(UUID eventId);
}

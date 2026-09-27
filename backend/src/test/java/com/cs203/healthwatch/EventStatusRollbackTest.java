package com.cs203.healthwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.model.AuditLog;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.AuditLogRepository;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * If writing the audit row fails, the status change must roll back too. The event UPDATE is flushed to the
 * database before the audit insert, so this proves a real database rollback, not just an unflushed change.
 */
@SpringBootTest
@ActiveProfiles("test")
class EventStatusRollbackTest {

    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @MockBean AuditLogRepository auditLogRepository;

    @Test
    void failedAuditWriteLeavesTheEventStatusUnchanged() {
        User admin = userRepository.save(new User("rollback-admin", "hash", "admin"));
        DetectedEvent event = eventRepository.save(
                TestEvents.event(4.2, EventStatus.NEW, "2026-09-24T03:00:00Z"));
        when(auditLogRepository.save(any(AuditLog.class)))
                .thenThrow(new DataIntegrityViolationException("simulated audit write failure"));

        assertThatThrownBy(() -> eventService.changeStatus(event.getId(), "UNDER_REVIEW", admin.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(auditLogRepository).save(any(AuditLog.class));
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.NEW);
    }
}

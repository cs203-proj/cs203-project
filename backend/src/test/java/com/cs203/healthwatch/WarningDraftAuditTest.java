package com.cs203.healthwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.service.EventService; 
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.service.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;   // @MockitoSpyBean on Boot 3.4+
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// Not @Transactional, same as EventStatusRollbackTest: real commits and rollbacks.
@SpringBootTest
class WarningDraftAuditTest {

    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void confirmingAnEventCreatesOneDraftWithOneSystemAuditEntry() {
        User admin = userRepository.save(new User("draft-admin-" + UUID.randomUUID(), "hash", "admin"));
        DetectedEvent event = eventRepository.save(
                TestEvents.event(4.2, EventStatus.UNDER_REVIEW, "2026-09-24T03:00:00Z"));

        eventService.changeStatus(event.getId(), "CONFIRMED", admin.getId());

        UUID warningId = jdbc.queryForObject(
                "select id from warnings where event_id = ?", UUID.class, event.getId());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select actor, action, before is null as before_null, after->>'status' as status "
                        + "from warning_audit_log where warning_id = ?", warningId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("actor", "system")
                .containsEntry("action", "DRAFT_CREATED")
                .containsEntry("before_null", true)
                .containsEntry("status", "PENDING_REVIEW");
    }
}
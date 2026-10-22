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

@SpringBootTest
class WarningDraftRollbackTest {

    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @MockBean AuditService auditService;   // plain mock, no transaction proxy

    @Test
    void failedAuditWriteRollsBackTheConfirmationAndTheDraft() {
        User admin = userRepository.save(new User("draft-admin-" + UUID.randomUUID(), "hash", "admin"));
        DetectedEvent event = eventRepository.save(
                TestEvents.event(4.2, EventStatus.UNDER_REVIEW, "2026-09-24T03:00:00Z"));
        doThrow(new DataIntegrityViolationException("simulated audit write failure"))
                .when(auditService).recordBySystem(any(UUID.class), anyString(), any(), any());

        assertThatThrownBy(() -> eventService.changeStatus(event.getId(), "CONFIRMED", admin.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(eventRepository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(EventStatus.UNDER_REVIEW);
        assertThat(jdbc.queryForObject(
                "select count(*) from warnings where event_id = ?", Integer.class, event.getId())).isZero();
    }
}

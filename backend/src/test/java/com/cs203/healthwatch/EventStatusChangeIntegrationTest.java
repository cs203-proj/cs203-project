package com.cs203.healthwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Whole application over HTTP with a real JWT, real transactions and an in-memory database: the admin lists
 * events, changes a status, and reads back the audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventStatusChangeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private User admin;
    private String adminToken;

    @BeforeEach
    void cleanDatabaseAndCreateAdmin() {
        jdbc.update("delete from audit_log");
        jdbc.update("delete from events");
        jdbc.update("delete from users");
        admin = userRepository.save(new User("admin", "hash", "admin"));
        adminToken = jwtService.generateToken("admin", "admin");
    }

    private DetectedEvent event(double deviation, EventStatus status) {
        return eventRepository.save(TestEvents.event(deviation, status, "2026-09-24T03:00:00Z"));
    }

    private ResultActions changeStatus(UUID eventId, String body, String token) throws Exception {
        return mockMvc.perform(patch("/events/{id}/status", eventId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions changeStatus(UUID eventId, String newStatus) throws Exception {
        return changeStatus(eventId, "{\"status\":\"" + newStatus + "\"}", adminToken);
    }

    private EventStatus statusInDb(UUID eventId) {
        return eventRepository.findById(eventId).orElseThrow().getStatus();
    }

    private List<Map<String, Object>> auditRows(UUID eventId) {
        return jdbc.queryForList(
                "select actor_id, old_status, new_status from audit_log where event_id = ? order by id", eventId);
    }

    // ------------------------------------------------------------------ CG-14: GET /events

    @Test
    void listIsSortedFilteredAndPagedFromTheDatabase() throws Exception {
        event(3.1, EventStatus.NEW);
        event(5.7, EventStatus.UNDER_REVIEW);
        event(4.2, EventStatus.NEW);

        mockMvc.perform(get("/events").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].deviationSize").value(org.hamcrest.Matchers.contains(5.7, 4.2, 3.1)))
                .andExpect(jsonPath("$.total").value(3));

        mockMvc.perform(get("/events").param("status", "new").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.items[*].deviationSize").value(org.hamcrest.Matchers.contains(4.2, 3.1)))
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get("/events").param("limit", "1").param("offset", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].deviationSize").value(4.2))
                .andExpect(jsonPath("$.total").value(3));

        mockMvc.perform(get("/events").param("status", "FOO").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Unknown status 'FOO'. Valid values: NEW, UNDER_REVIEW, CONFIRMED, DISMISSED"));

        mockMvc.perform(get("/events").param("limit", "201").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200"));
    }

    @Test
    void emptyListIs200() throws Exception {
        mockMvc.perform(get("/events").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));
    }

    // ------------------------------------------------------------------ CG-35: PATCH /events/{id}/status

    @Test
    void validTransitionUpdatesStatusAndWritesExactlyOneAuditRow() throws Exception {
        DetectedEvent event = event(4.2, EventStatus.NEW);

        changeStatus(event.getId(), "under_review")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(event.getId().toString()))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.deviationSize").value(4.2));

        assertThat(statusInDb(event.getId())).isEqualTo(EventStatus.UNDER_REVIEW);
        assertThat(auditRows(event.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("actor_id")).isEqualTo(admin.getId());
            assertThat(row.get("old_status")).isEqualTo("NEW");
            assertThat(row.get("new_status")).isEqualTo("UNDER_REVIEW");
        });
    }

    @Test
    void invalidTransitionIs400AndChangesNothing() throws Exception {
        DetectedEvent event = event(4.2, EventStatus.NEW);

        changeStatus(event.getId(), "CONFIRMED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Cannot change status from NEW to CONFIRMED. Allowed from NEW: UNDER_REVIEW, DISMISSED"));

        assertThat(statusInDb(event.getId())).isEqualTo(EventStatus.NEW);
        assertThat(auditRows(event.getId())).isEmpty();
    }

    @Test
    void sameStatusIs400AndChangesNothing() throws Exception {
        DetectedEvent event = event(4.2, EventStatus.UNDER_REVIEW);

        changeStatus(event.getId(), "UNDER_REVIEW")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Event is already UNDER_REVIEW"));

        assertThat(auditRows(event.getId())).isEmpty();
    }

    @Test
    void confirmedIsFinal() throws Exception {
        DetectedEvent event = event(4.2, EventStatus.CONFIRMED);

        changeStatus(event.getId(), "DISMISSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Cannot change status from CONFIRMED to DISMISSED. Allowed from CONFIRMED: none, CONFIRMED "
                                + "is final"));
    }

    @Test
    void unknownStatusValueIs400() throws Exception {
        DetectedEvent event = event(4.2, EventStatus.NEW);

        changeStatus(event.getId(), "FOO").andExpect(status().isBadRequest());

        assertThat(statusInDb(event.getId())).isEqualTo(EventStatus.NEW);
    }

    @Test
    void unknownEventIs404() throws Exception {
        changeStatus(UUID.randomUUID(), "UNDER_REVIEW").andExpect(status().isNotFound());
    }

    @Test
    void actorComesFromTheToken_evenIfTheBodyNamesSomeoneElse() throws Exception {
        User other = userRepository.save(new User("other-admin", "hash", "admin"));
        DetectedEvent event = event(4.2, EventStatus.NEW);

        changeStatus(event.getId(), "{\"status\":\"DISMISSED\",\"actor\":\"" + other.getId() + "\",\"actorId\":\""
                + other.getId() + "\"}", adminToken)
                .andExpect(status().isOk());

        assertThat(auditRows(event.getId())).singleElement()
                .satisfies(row -> assertThat(row.get("actor_id")).isEqualTo(admin.getId()));
    }

    @Test
    void unauthorizedAndForbiddenChangeNothing() throws Exception {
        userRepository.save(new User("viewer", "hash", "viewer"));
        DetectedEvent event = event(4.2, EventStatus.NEW);

        mockMvc.perform(patch("/events/{id}/status", event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isUnauthorized());
        changeStatus(event.getId(), "{\"status\":\"UNDER_REVIEW\"}", jwtService.generateToken("viewer", "viewer"))
                .andExpect(status().isForbidden());

        assertThat(statusInDb(event.getId())).isEqualTo(EventStatus.NEW);
        assertThat(auditRows(event.getId())).isEmpty();
    }

    // ------------------------------------------------------------------ CG-35: GET /events/{id}/audit

    @Test
    void auditTrailListsEveryChangeInTheOrderItHappened() throws Exception {
        DetectedEvent event = event(4.2, EventStatus.NEW);
        changeStatus(event.getId(), "UNDER_REVIEW").andExpect(status().isOk());
        changeStatus(event.getId(), "DISMISSED").andExpect(status().isOk());
        changeStatus(event.getId(), "UNDER_REVIEW").andExpect(status().isOk()); // reopen
        changeStatus(event.getId(), "CONFIRMED").andExpect(status().isOk());

        mockMvc.perform(get("/events/{id}/audit", event.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].oldStatus")
                        .value(org.hamcrest.Matchers.contains("NEW", "UNDER_REVIEW", "DISMISSED", "UNDER_REVIEW")))
                .andExpect(jsonPath("$[*].newStatus")
                        .value(org.hamcrest.Matchers.contains("UNDER_REVIEW", "DISMISSED", "UNDER_REVIEW", "CONFIRMED")))
                .andExpect(jsonPath("$[0].actorId").value(admin.getId().toString()))
                .andExpect(jsonPath("$[0].actorUsername").value("admin"))
                .andExpect(jsonPath("$[0].changedAt").exists());
    }

    @Test
    void auditTrailOfUntouchedEventIsEmpty_andOfUnknownEventIs404() throws Exception {
        DetectedEvent event = event(4.2, EventStatus.NEW);

        mockMvc.perform(get("/events/{id}/audit", event.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/events/{id}/audit", UUID.randomUUID()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}

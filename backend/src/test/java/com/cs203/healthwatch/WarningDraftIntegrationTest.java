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
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import java.util.List;
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

/** CG-21: confirming an event over HTTP drafts exactly one general-public warning, pending review. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarningDraftIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired EventRepository eventRepository;
    @Autowired WarningRepository warningRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void cleanDatabaseAndCreateAdmin() {
        jdbc.update("delete from warning_audit_log"); // append-only in Postgres; H2 test copy has no trigger
        jdbc.update("delete from warnings");
        jdbc.update("delete from audit_log");
        jdbc.update("delete from events");
        jdbc.update("delete from users");
        userRepository.save(new User("admin", "hash", "admin"));
        adminToken = jwtService.generateToken("admin", "admin");
    }

    private DetectedEvent event(double deviation, EventStatus status) {
        return eventRepository.save(TestEvents.event(deviation, status, "2026-09-24T03:15:00Z"));
    }

    private ResultActions changeStatus(UUID eventId, String newStatus) throws Exception {
        return mockMvc.perform(patch("/events/{id}/status", eventId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + newStatus + "\"}"));
    }

    private List<Warning> warningsFor(UUID eventId) {
        return warningRepository.findByEventIdOrderByCreatedAtAscIdAsc(eventId);
    }

    @Test
    void confirmingAnEventDraftsOnePendingGeneralPublicWarning() throws Exception {
        DetectedEvent event = event(9.4, EventStatus.UNDER_REVIEW);

        changeStatus(event.getId(), "CONFIRMED").andExpect(status().isOk());

        assertThat(warningsFor(event.getId())).singleElement().satisfies(w -> {
            assertThat(w.getAudience()).isEqualTo("GENERAL_PUBLIC");
            assertThat(w.getSeverity()).isEqualTo(WarningSeverity.HIGH);
            assertThat(w.getStatus()).isEqualTo(WarningStatus.PENDING_REVIEW);
            assertThat(w.getWarningText()).contains("What happened:", "Confidence:", "What you should do:");
        });
    }

    @Test
    void draftIsRecordedInTheWarningAuditTrailBySystem() throws Exception {
        DetectedEvent event = event(9.4, EventStatus.UNDER_REVIEW);
        changeStatus(event.getId(), "CONFIRMED").andExpect(status().isOk());
        Warning warning = warningsFor(event.getId()).get(0);

        mockMvc.perform(get("/warnings/{id}/audit", warning.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].actor").value("system"))
                .andExpect(jsonPath("$[0].action").value("DRAFT_CREATED"))
                .andExpect(jsonPath("$[0].before").doesNotExist())
                .andExpect(jsonPath("$[0].after.eventId").value(event.getId().toString()))
                .andExpect(jsonPath("$[0].after.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$[0].after.severity").value("HIGH"))
                .andExpect(jsonPath("$[0].after.warningText").value(warning.getWarningText()));
    }

    @Test
    void otherTransitionsDraftNothing() throws Exception {
        DetectedEvent event = event(9.4, EventStatus.NEW);

        changeStatus(event.getId(), "UNDER_REVIEW").andExpect(status().isOk());
        changeStatus(event.getId(), "DISMISSED").andExpect(status().isOk());
        changeStatus(event.getId(), "UNDER_REVIEW").andExpect(status().isOk());

        assertThat(warningsFor(event.getId())).isEmpty();
    }

    @Test
    void rejectedConfirmDraftsNothing() throws Exception {
        DetectedEvent event = event(9.4, EventStatus.NEW);

        changeStatus(event.getId(), "CONFIRMED").andExpect(status().isBadRequest());

        assertThat(warningsFor(event.getId())).isEmpty();
    }

    @Test
    void originalDraftTextCannotBeOverwritten() throws Exception {
        DetectedEvent event = event(9.4, EventStatus.UNDER_REVIEW);
        changeStatus(event.getId(), "CONFIRMED").andExpect(status().isOk());
        Warning warning = warningsFor(event.getId()).get(0);
        String original = warning.getWarningText();

        warning.setWarningText("Edited by someone");
        warningRepository.saveAndFlush(warning);

        assertThat(warningRepository.findById(warning.getId()).orElseThrow().getWarningText()).isEqualTo(original);
    }

    @Test
    void getWarningsReturnsTheDraft_emptyBeforeConfirm_and404ForUnknownEvent() throws Exception {
        DetectedEvent event = event(5.5, EventStatus.UNDER_REVIEW);

        mockMvc.perform(get("/events/{id}/warnings", event.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        changeStatus(event.getId(), "CONFIRMED").andExpect(status().isOk());

        mockMvc.perform(get("/events/{id}/warnings", event.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventId").value(event.getId().toString()))
                .andExpect(jsonPath("$[0].audience").value("GENERAL_PUBLIC"))
                .andExpect(jsonPath("$[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$[0].warningText").isNotEmpty())
                .andExpect(jsonPath("$[0].createdAt").exists());

        mockMvc.perform(get("/events/{id}/warnings", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getWarningsNeedsAnAdminToken() throws Exception {
        userRepository.save(new User("viewer", "hash", "viewer"));
        DetectedEvent event = event(5.5, EventStatus.CONFIRMED);

        mockMvc.perform(get("/events/{id}/warnings", event.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/events/{id}/warnings", event.getId())
                        .header("Authorization", "Bearer " + jwtService.generateToken("viewer", "viewer")))
                .andExpect(status().isForbidden());
    }
}

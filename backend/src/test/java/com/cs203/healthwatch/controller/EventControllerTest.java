package com.cs203.healthwatch.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.AuditEntryResponse;
import com.cs203.healthwatch.dto.EventListResponse;
import com.cs203.healthwatch.dto.EventResponse;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import com.cs203.healthwatch.service.EventService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web layer only: request parsing, JSON shape, error mapping and security (real JWT filter and SecurityConfig).
 * EventService is mocked; its behaviour against a database is covered by EventStatusChangeIntegrationTest and
 * EventRepositoryTest.
 */
@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, JwtService.class})
class EventControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID EVENT_ID = UUID.fromString("3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b");

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean EventService eventService;
    @MockBean UserRepository userRepository;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void users() {
        User admin = new User("admin", "hash", "admin");
        admin.setId(ADMIN_ID);
        User viewer = new User("viewer", "hash", "viewer");
        viewer.setId(UUID.randomUUID());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByUsername("viewer")).thenReturn(Optional.of(viewer));

        adminToken = jwtService.generateToken("admin", "admin");
        viewerToken = jwtService.generateToken("viewer", "viewer");
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private static EventResponse event(double deviation, EventStatus status) {
        return new EventResponse(UUID.randomUUID(), Instant.parse("2026-09-24T03:00:00Z"),
                "AQI", "Singapore", status, deviation);
    }

    private void serviceReturns(List<EventResponse> items, long total) {
        when(eventService.list(any(), anyInt(), anyLong())).thenAnswer(inv ->
                new EventListResponse(items, total, inv.getArgument(1), inv.getArgument(2)));
    }

    // ------------------------------------------------------------------ GET /events

    @Test
    void list_returnsTheFieldsTheAdminNeeds_inServiceOrder() throws Exception {
        serviceReturns(List.of(event(5.7, EventStatus.NEW), event(4.2, EventStatus.UNDER_REVIEW)), 2);

        mockMvc.perform(asAdmin(get("/events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.items[0].timestamp").value("2026-09-24T03:00:00Z"))
                .andExpect(jsonPath("$.items[0].signalType").value("AQI"))
                .andExpect(jsonPath("$.items[0].region").value("Singapore"))
                .andExpect(jsonPath("$.items[0].status").value("NEW"))
                .andExpect(jsonPath("$.items[0].deviationSize").value(5.7))
                .andExpect(jsonPath("$.items[1].deviationSize").value(4.2))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void list_appliesDefaultLimitAndOffset() throws Exception {
        serviceReturns(List.of(), 0);

        mockMvc.perform(asAdmin(get("/events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0));

        verify(eventService).list(isNull(), eq(50), eq(0L));
    }

    @Test
    void list_passesFilterLimitAndOffsetThrough() throws Exception {
        serviceReturns(List.of(), 0);

        mockMvc.perform(asAdmin(get("/events").param("status", "new").param("limit", "2").param("offset", "4")))
                .andExpect(status().isOk());

        verify(eventService).list(eq("new"), eq(2), eq(4L));
    }

    @Test
    void list_nonNumericLimitIs400() throws Exception {
        mockMvc.perform(asAdmin(get("/events").param("limit", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'abc' for limit"));

        verifyNoInteractions(eventService);
    }

    @Test
    void list_badRequestFromServiceIsReturnedAsApiError() throws Exception {
        when(eventService.list(any(), anyInt(), anyLong()))
                .thenThrow(new BadRequestException("Unknown status 'FOO'. Valid values: NEW, UNDER_REVIEW, "
                        + "CONFIRMED, DISMISSED"));

        mockMvc.perform(asAdmin(get("/events").param("status", "FOO")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Unknown status 'FOO'")));
    }

    // ------------------------------------------------------------------ PATCH /events/{id}/status

    @Test
    void patch_passesTheActorFromTheToken_notFromTheBody() throws Exception {
        when(eventService.changeStatus(any(), any(), any())).thenReturn(
                new EventResponse(EVENT_ID, Instant.parse("2026-09-24T03:00:00Z"), "AQI", "Singapore",
                        EventStatus.UNDER_REVIEW, 4.2));
        UUID spoofed = UUID.randomUUID();

        mockMvc.perform(asAdmin(patch("/events/{id}/status", EVENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNDER_REVIEW\",\"actor\":\"" + spoofed + "\",\"actorId\":\""
                                + spoofed + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        verify(eventService).changeStatus(EVENT_ID, "UNDER_REVIEW", ADMIN_ID);
    }

    @Test
    void patch_missingStatusIs400WithFieldDetail() throws Exception {
        mockMvc.perform(asAdmin(patch("/events/{id}/status", EVENT_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("status: status is required"));

        verifyNoInteractions(eventService);
    }

    @Test
    void patch_missingOrMalformedBodyIs400() throws Exception {
        mockMvc.perform(asAdmin(patch("/events/{id}/status", EVENT_ID)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asAdmin(patch("/events/{id}/status", EVENT_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(eventService);
    }

    @Test
    void patch_malformedIdIs400() throws Exception {
        mockMvc.perform(asAdmin(patch("/events/{id}/status", "not-a-uuid"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_unknownEventIs404() throws Exception {
        when(eventService.changeStatus(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Event " + EVENT_ID + " not found"));

        mockMvc.perform(asAdmin(patch("/events/{id}/status", EVENT_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Event " + EVENT_ID + " not found"));
    }

    // ------------------------------------------------------------------ GET /events/{id}/audit

    @Test
    void audit_returnsEntriesAsGivenByService() throws Exception {
        when(eventService.auditTrail(EVENT_ID)).thenReturn(List.of(
                new AuditEntryResponse(1L, ADMIN_ID, "admin", EventStatus.NEW, EventStatus.UNDER_REVIEW,
                        Instant.parse("2026-09-24T04:00:00Z")),
                new AuditEntryResponse(2L, ADMIN_ID, "admin", EventStatus.UNDER_REVIEW, EventStatus.CONFIRMED,
                        Instant.parse("2026-09-24T05:00:00Z"))));

        mockMvc.perform(asAdmin(get("/events/{id}/audit", EVENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].actorId").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$[0].actorUsername").value("admin"))
                .andExpect(jsonPath("$[0].oldStatus").value("NEW"))
                .andExpect(jsonPath("$[0].newStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$[0].changedAt").value("2026-09-24T04:00:00Z"))
                .andExpect(jsonPath("$[1].newStatus").value("CONFIRMED"));
    }

    @Test
    void audit_unknownEventIs404() throws Exception {
        when(eventService.auditTrail(EVENT_ID)).thenThrow(new ResourceNotFoundException("Event not found"));

        mockMvc.perform(asAdmin(get("/events/{id}/audit", EVENT_ID))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ security (all three endpoints)

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(get("/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/events/{id}/audit", EVENT_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/events/{id}/status", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(eventService);
    }

    @Test
    void invalidTokenIs401() throws Exception {
        mockMvc.perform(get("/events").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(eventService);
    }

    @Test
    void nonAdminTokenIs403() throws Exception {
        mockMvc.perform(get("/events").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/events/{id}/audit", EVENT_ID).header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/events/{id}/status", EVENT_ID).header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(eventService);
    }
}

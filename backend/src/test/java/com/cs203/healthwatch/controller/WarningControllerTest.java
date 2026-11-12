package com.cs203.healthwatch.controller;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.WarningListResponse;
import com.cs203.healthwatch.dto.WarningResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import com.cs203.healthwatch.service.WarningService;
import com.cs203.healthwatch.service.WarningReviewService;
import com.cs203.healthwatch.service.AuditService;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;

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

/**
 * Web layer only: query params, JSON shape, error mapping and security (real JWT filter and SecurityConfig).
 * WarningService is mocked; the queue order is covered by WarningRepositoryTest.
 */
@WebMvcTest(WarningController.class)
@Import({SecurityConfig.class, JwtService.class})
class WarningControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean WarningService warningService;
    @MockBean WarningReviewService warningReviewService;
    @MockBean AuditService auditService;
    @MockBean UserRepository userRepository;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void users() {
        User admin = new User("admin", "hash", "admin");
        admin.setId(UUID.randomUUID());
        User viewer = new User("viewer", "hash", "viewer");
        viewer.setId(UUID.randomUUID());
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(viewer.getId())).thenReturn(Optional.of(viewer));

        adminToken = jwtService.generateToken(admin.getId(), "admin", "admin");
        viewerToken = jwtService.generateToken(viewer.getId(), "viewer", "viewer");
    }

    private static WarningResponse warning(WarningSeverity severity) {
        return new WarningResponse(UUID.randomUUID(), UUID.randomUUID(), "GENERAL_PUBLIC", severity,
                WarningStatus.PENDING_REVIEW, "PM2.5 is elevated.", null, null,
                Instant.parse("2026-10-14T02:10:00Z"), null);
    }

    @Test
    void queue_returnsServiceOrderAndFields() throws Exception {
        when(warningService.queue(any(), anyInt(), anyLong())).thenReturn(new WarningListResponse(
                List.of(warning(WarningSeverity.CRITICAL), warning(WarningSeverity.LOW)), 2, 50, 0));

        mockMvc.perform(get("/warnings").param("status", "PENDING_REVIEW")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.items[0].warningText").value("PM2.5 is elevated."))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-14T02:10:00Z"))
                .andExpect(jsonPath("$.items[1].severity").value("LOW"))
                .andExpect(jsonPath("$.total").value(2));

        verify(warningService).queue(eq("PENDING_REVIEW"), eq(50), eq(0L));
    }

    @Test
    void queue_defaultsToAllStatusesFirstPage() throws Exception {
        when(warningService.queue(any(), anyInt(), anyLong()))
                .thenReturn(new WarningListResponse(List.of(), 0, 50, 0));

        mockMvc.perform(get("/warnings").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        verify(warningService).queue(isNull(), eq(50), eq(0L));
    }

    @Test
    void queue_withoutTokenIs401() throws Exception {
        mockMvc.perform(get("/warnings").param("status", "PENDING_REVIEW"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(warningService);
    }

    @Test
    void queue_invalidTokenIs401() throws Exception {
        mockMvc.perform(get("/warnings").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(warningService);
    }

    @Test
    void queue_nonAdminIs403() throws Exception {
        mockMvc.perform(get("/warnings").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        verifyNoInteractions(warningService);
    }

    @Test
    void queue_unknownStatusIs400ApiError() throws Exception {
        when(warningService.queue(any(), anyInt(), anyLong())).thenThrow(new BadRequestException(
                "Unknown status 'FOO'. Valid values: PENDING_REVIEW, APPROVED, SUPPRESSED"));

        mockMvc.perform(get("/warnings").param("status", "FOO").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // --- review actions (CG-28) ---------------------------------------------------------------------------------

    @Test
    void approve_passesTheTokenUserAsActor() throws Exception {
        UUID id = UUID.randomUUID();
        when(warningReviewService.approve(any(), any())).thenReturn(warning(WarningSeverity.HIGH));

        mockMvc.perform(post("/warnings/{id}/approve", id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        verify(warningReviewService).approve(eq(id), any(UUID.class));
    }

    @Test
    void edit_passesTheNewTextAndReturnsBothVersions() throws Exception {
        UUID id = UUID.randomUUID();
        when(warningReviewService.edit(any(), any(), any())).thenReturn(new WarningResponse(
                id, UUID.randomUUID(), "GENERAL_PUBLIC", WarningSeverity.HIGH, WarningStatus.PENDING_REVIEW,
                "PM2.5 is elevated.", "Air quality is poor. Stay indoors.", null,
                Instant.parse("2026-10-14T02:10:00Z"), null));

        mockMvc.perform(post("/warnings/{id}/edit", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warningText\":\"Air quality is poor. Stay indoors.\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warningText").value("PM2.5 is elevated."))
                .andExpect(jsonPath("$.editedText").value("Air quality is poor. Stay indoors."));

        verify(warningReviewService).edit(eq(id), eq("Air quality is poor. Stay indoors."), any(UUID.class));
    }

    @Test
    void edit_blankTextIs400() throws Exception {
        mockMvc.perform(post("/warnings/{id}/edit", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warningText\":\"  \"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(warningReviewService);
    }

    @Test
    void downgrade_passesSeverityWordingAndReason() throws Exception {
        UUID id = UUID.randomUUID();
        when(warningReviewService.downgrade(any(), any(), any(), any(), any()))
                .thenReturn(warning(WarningSeverity.MEDIUM));

        mockMvc.perform(post("/warnings/{id}/downgrade", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"MEDIUM\",\"warningText\":\"Levels are elevated (medium severity).\","
                                + "\"reason\":\"Readings fell back to baseline.\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        verify(warningReviewService).downgrade(eq(id), eq("MEDIUM"),
                eq("Levels are elevated (medium severity)."), eq("Readings fell back to baseline."),
                any(UUID.class));
    }

    @Test
    void downgrade_withoutReasonIs400() throws Exception {
        mockMvc.perform(post("/warnings/{id}/downgrade", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"MEDIUM\",\"warningText\":\"Levels are elevated.\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(warningReviewService);
    }

    /** A downgrade without new wording would leave the old severity's advice on the warning. */
    @Test
    void downgrade_withoutNewWordingIs400() throws Exception {
        mockMvc.perform(post("/warnings/{id}/downgrade", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"MEDIUM\",\"reason\":\"Readings fell back to baseline.\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(warningReviewService);
    }

    @Test
    void suppress_requiresAReason() throws Exception {
        mockMvc.perform(post("/warnings/{id}/suppress", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(warningReviewService);
    }

    @Test
    void suppress_passesTheReason() throws Exception {
        UUID id = UUID.randomUUID();
        when(warningReviewService.suppress(any(), any(), any())).thenReturn(warning(WarningSeverity.HIGH));

        mockMvc.perform(post("/warnings/{id}/suppress", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate of an approved warning.\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        verify(warningReviewService).suppress(eq(id), eq("Duplicate of an approved warning."), any(UUID.class));
    }

    @Test
    void reviewActions_withoutTokenAre401() throws Exception {
        mockMvc.perform(post("/warnings/{id}/approve", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(warningReviewService);
    }

    @Test
    void reviewActions_asNonAdminAre403() throws Exception {
        mockMvc.perform(post("/warnings/{id}/approve", UUID.randomUUID())
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        verifyNoInteractions(warningReviewService);
    }
}

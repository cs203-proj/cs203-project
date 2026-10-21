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
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByUsername("viewer")).thenReturn(Optional.of(viewer));

        adminToken = jwtService.generateToken("admin", "admin");
        viewerToken = jwtService.generateToken("viewer", "viewer");
    }

    private static WarningResponse warning(WarningSeverity severity) {
        return new WarningResponse(UUID.randomUUID(), UUID.randomUUID(), "GENERAL_PUBLIC", severity,
                WarningStatus.PENDING_REVIEW, "PM2.5 is elevated.", Instant.parse("2026-10-14T02:10:00Z"));
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
}

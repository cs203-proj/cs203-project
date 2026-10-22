package com.cs203.healthwatch.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.BackfillResponse;
import com.cs203.healthwatch.ingestion.pm.PmBackfillService;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import java.time.LocalDate;
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

/** Web layer only: date parsing, JSON shape and admin-only access. PmBackfillService is mocked. */
@WebMvcTest(BackfillController.class)
@Import({SecurityConfig.class, JwtService.class})
class BackfillControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean PmBackfillService backfillService;
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

    @Test
    void adminCanBackfill_andGetsTheSummary() throws Exception {
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-09-02");
        when(backfillService.backfill(from, to))
                .thenReturn(new BackfillResponse(from, to, 2, 2, 96, 0, 0, 0, List.of()));

        mockMvc.perform(post("/backfill/pm25").param("from", "2026-09-01").param("to", "2026-09-02")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(2))
                .andExpect(jsonPath("$.saved").value(96))
                .andExpect(jsonPath("$.failedDays").isEmpty());

        verify(backfillService).backfill(from, to);
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mockMvc.perform(post("/backfill/pm25").param("from", "2026-09-01").param("to", "2026-09-02"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(backfillService);
    }

    @Test
    void nonAdminIs403() throws Exception {
        mockMvc.perform(post("/backfill/pm25").param("from", "2026-09-01").param("to", "2026-09-02")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        verifyNoInteractions(backfillService);
    }

    @Test
    void malformedDateIs400() throws Exception {
        mockMvc.perform(post("/backfill/pm25").param("from", "01-09-2026").param("to", "2026-09-02")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(backfillService);
    }

    @Test
    void invalidRangeFromServiceIs400ApiError() throws Exception {
        when(backfillService.backfill(any(), any())).thenThrow(new BadRequestException("from must be on or before to"));

        mockMvc.perform(post("/backfill/pm25").param("from", "2026-09-05").param("to", "2026-09-01")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from must be on or before to"));
    }
}

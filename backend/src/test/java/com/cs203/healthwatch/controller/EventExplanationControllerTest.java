package com.cs203.healthwatch.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.EventExplanation;
import com.cs203.healthwatch.explanation.EventExplanationService;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
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

@WebMvcTest(EventExplanationController.class)
@Import({SecurityConfig.class, JwtService.class})
class EventExplanationControllerTest {

    private static final UUID EVENT_ID = UUID.fromString("3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b");

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean EventExplanationService explanationService;
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
    void returnsTheExplanation() throws Exception {
        EventExplanation e = new EventExplanation();
        e.setEventId(EVENT_ID);
        e.setStatus(ExplanationStatus.OK);
        e.setAssessment(Assessment.GENUINE_TREND);
        e.setLikelyCause("Consistent with regional haze");
        e.setExplanation("PM2.5 rose steadily.");
        e.setEvidence(List.of("45, 55, 70 vs median 18"));
        e.setAssumptions(List.of("Station reports correctly"));
        e.setDataComplete(false);
        e.setDataQualityNote("Based on partial data: 4 of 6 expected readings in the last 6 hours.");
        e.setModel("groq-test-model");
        when(explanationService.getForEvent(EVENT_ID)).thenReturn(e);

        mockMvc.perform(get("/events/{id}/explanation", EVENT_ID).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.assessment").value("GENUINE_TREND"))
                .andExpect(jsonPath("$.evidence[0]").value("45, 55, 70 vs median 18"))
                .andExpect(jsonPath("$.dataComplete").value(false))
                .andExpect(jsonPath("$.dataQualityNote").value(
                        "Based on partial data: 4 of 6 expected readings in the last 6 hours."));
    }

    @Test
    void noExplanationYet_is404() throws Exception {
        when(explanationService.getForEvent(EVENT_ID))
                .thenThrow(new ResourceNotFoundException("Event " + EVENT_ID + " has no explanation yet"));

        mockMvc.perform(get("/events/{id}/explanation", EVENT_ID).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void needsAnAdminToken() throws Exception {
        mockMvc.perform(get("/events/{id}/explanation", EVENT_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/events/{id}/explanation", EVENT_ID).header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }
}

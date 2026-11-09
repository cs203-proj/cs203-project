package com.cs203.healthwatch.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.dto.MyWarningListResponse;
import com.cs203.healthwatch.dto.MyWarningResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import com.cs203.healthwatch.service.MyWarningService;
import com.cs203.healthwatch.warnings.WarningSeverity;
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

/** Web layer only: security, JSON shape and that the user id comes from the token. */
@WebMvcTest(MeWarningController.class)
@Import({SecurityConfig.class, JwtService.class})
class MeWarningControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean MyWarningService myWarningService;
    @MockBean UserRepository userRepository;

    private User user;
    private String token;

    @BeforeEach
    void login() {
        user = new User("alice", "hash", "viewer");
        user.setId(UUID.randomUUID());
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        token = jwtService.generateToken("alice", "viewer");
    }

    @Test
    void noTokenGives401() throws Exception {
        mockMvc.perform(get("/me/warnings")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenGives401() throws Exception {
        mockMvc.perform(get("/me/warnings").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsWarningWithConfidenceReasonAndLabelledAiLine() throws Exception {
        MyWarningResponse item = new MyWarningResponse(UUID.randomUUID(), UUID.randomUUID(), WarningSeverity.HIGH,
                "east", "VULNERABLE", "Stay indoors.", Instant.parse("2026-10-14T02:40:00Z"),
                new MyWarningResponse.Confidence(ConfidenceLevel.MEDIUM, "Moderate confidence."),
                "You are in the east region and receive advice for vulnerable groups.",
                new MyWarningResponse.AiLine("Keep your inhaler close.", "AI-generated"),
                "Based on partial data: 4 of 6 expected readings in the last 6 hours.");
        when(myWarningService.forUser(user.getId())).thenReturn(new MyWarningListResponse(List.of(item), null));

        mockMvc.perform(get("/me/warnings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.items[0].text").value("Stay indoors."))
                .andExpect(jsonPath("$.items[0].confidence.level").value("MEDIUM"))
                .andExpect(jsonPath("$.items[0].whyThisApplies")
                        .value("You are in the east region and receive advice for vulnerable groups."))
                .andExpect(jsonPath("$.items[0].aiLine.label").value("AI-generated"))
                .andExpect(jsonPath("$.items[0].dataQualityNote").exists());
        verify(myWarningService).forUser(user.getId());
    }

    @Test
    void incompleteProfileGives200WithEmptyListAndMessage() throws Exception {
        when(myWarningService.forUser(user.getId())).thenReturn(
                new MyWarningListResponse(List.of(), "Complete your profile (region and audience) to see warnings that apply to you."));

        mockMvc.perform(get("/me/warnings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Complete your profile")));
    }
}

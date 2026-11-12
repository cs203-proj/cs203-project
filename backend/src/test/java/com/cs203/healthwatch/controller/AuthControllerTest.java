package com.cs203.healthwatch.controller;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.consent.ConsentTerms;
import com.cs203.healthwatch.dto.RegisterRequest;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtAuthFilter;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import com.cs203.healthwatch.service.RegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private RegistrationService registrationService;

    // needed because SecurityConfig wires this in — @WebMvcTest loads security config too
    @SpyBean
    private JwtAuthFilter jwtAuthFilter;

    @Test
    void validLogin_returnsToken() throws Exception {
        User user = new User("admin", "hashed-password", "admin");
        user.setId(UUID.randomUUID());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hashed-password")).thenReturn(true);
        when(jwtService.generateToken(user.getId(), "admin", "admin")).thenReturn("fake.jwt.token");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "admin", "password", "correct-password"))))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPassword_returnsGenericUnauthorized() throws Exception {
        User user = new User("admin", "hashed-password", "admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "admin", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUsername_returnsSameGenericUnauthorized() throws Exception {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "ghost", "password", "whatever"))))
                .andExpect(status().isUnauthorized());
    }

    // ---- CG-5 / CG-8 ----

    private String registerBody(String username, String password, Boolean consent, String version)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("consent", consent);
        body.put("consentVersion", version);
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void register_withoutToken_createsAccountAndReturns201() throws Exception {
        User saved = new User("alex.tan", "bcrypt-hash", "viewer");
        saved.setId(UUID.randomUUID());
        saved.setConsentVersion(ConsentTerms.VERSION);
        saved.setConsentedAt(Instant.parse("2026-11-10T08:11:00Z"));
        when(registrationService.register(any(RegisterRequest.class))).thenReturn(saved);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("alex.tan", "password123", true, ConsentTerms.VERSION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alex.tan"))
                .andExpect(jsonPath("$.role").value("viewer"))
                .andExpect(jsonPath("$.consentVersion").value(ConsentTerms.VERSION))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void register_shortPassword_is400AndServiceNotCalled() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("alex.tan", "short", true, ConsentTerms.VERSION)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }

    @Test
    void register_missingUsername_is400() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("", "password123", true, ConsentTerms.VERSION)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }

    @Test
    void register_consentNotGiven_is400() throws Exception {
        when(registrationService.register(any(RegisterRequest.class)))
                .thenThrow(new BadRequestException("Consent is required to create an account."));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("alex.tan", "password123", false, ConsentTerms.VERSION)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_duplicateUsername_is409() throws Exception {
        when(registrationService.register(any(RegisterRequest.class)))
                .thenThrow(new ConflictException("Username 'alex.tan' is already taken"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("alex.tan", "password123", true, ConsentTerms.VERSION)))
                .andExpect(status().isConflict());
    }

    @Test
    void logout_isPublicAndReturns204() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isNoContent());
    }
}

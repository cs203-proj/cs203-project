package com.cs203.healthwatch.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.ProfileResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import com.cs203.healthwatch.service.ProfileService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web layer: security (real JWT filter), token-derived user id and status mapping. ProfileService is mocked. */
@WebMvcTest(MeController.class)
@Import({SecurityConfig.class, JwtService.class})
class MeControllerTest {

    private static final UUID ALICE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String BODY = "{\"region\":\"north\",\"audience\":\"VULNERABLE\",\"healthConditions\":[\"ASTHMA\"]}";

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean ProfileService profileService;
    @MockBean UserRepository userRepository;

    private String aliceToken;

    @BeforeEach
    void users() {
        User alice = new User("alice", "hash", "user");
        alice.setId(ALICE_ID);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        aliceToken = jwtService.generateToken("alice", "user");
    }

    @Test
    void noToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/me/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/me/profile").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profileService);
    }

    @Test
    void invalidToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/me/profile").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_usesTheUserFromTheToken() throws Exception {
        when(profileService.get(ALICE_ID)).thenReturn(new ProfileResponse("north", "VULNERABLE", List.of("ASTHMA")));

        mockMvc.perform(get("/me/profile").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("north"))
                .andExpect(jsonPath("$.audience").value("VULNERABLE"))
                .andExpect(jsonPath("$.healthConditions[0]").value("ASTHMA"));
    }

    @Test
    void put_usesTheUserFromTheToken() throws Exception {
        when(profileService.update(Mockito.eq(ALICE_ID), any()))
                .thenReturn(new ProfileResponse("north", "VULNERABLE", List.of("ASTHMA")));

        mockMvc.perform(put("/me/profile").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(profileService).update(Mockito.eq(ALICE_ID), any());
    }

    @Test
    void put_unknownCode_isBadRequestWithMessage() throws Exception {
        when(profileService.update(Mockito.eq(ALICE_ID), any()))
                .thenThrow(new BadRequestException("region: unknown value 'mars'"));

        mockMvc.perform(put("/me/profile").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("region: unknown value 'mars'"));
    }
}

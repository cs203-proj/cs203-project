package com.cs203.healthwatch.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.consent.ConsentTerms;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The consent notice has to be readable before anyone has an account, so this checks it is public and that it
 * actually says the three things CG-8 requires.
 */
@WebMvcTest(ConsentController.class)
@Import({SecurityConfig.class, JwtService.class})
class ConsentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean UserRepository userRepository;

    @Test
    void isPublicAndReturnsTheCurrentVersionAndText() throws Exception {
        mockMvc.perform(get("/consent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(ConsentTerms.VERSION))
                .andExpect(jsonPath("$.text").value(ConsentTerms.TEXT));
    }

    @Test
    void saysHealthDataIsUsedOnlyToPersonaliseWarnings() throws Exception {
        mockMvc.perform(get("/consent"))
                .andExpect(jsonPath("$.text").value(containsString("which public health warnings are relevant")));
    }

    @Test
    void saysItIsNeverUsedForDiagnosis() throws Exception {
        mockMvc.perform(get("/consent"))
                .andExpect(jsonPath("$.text").value(containsString("never used to diagnose you")));
    }

    @Test
    void saysTheUserCanDeleteTheirProfile() throws Exception {
        mockMvc.perform(get("/consent"))
                .andExpect(jsonPath("$.text").value(containsString("you can delete it")));
    }
}

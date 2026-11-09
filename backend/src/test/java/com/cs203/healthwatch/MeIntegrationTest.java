package com.cs203.healthwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.model.AuditLog;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.AuditLogRepository;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** CG-6 end to end: real security, JPA and database (H2 test profile). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeIntegrationTest {

    private static final String PROFILE =
            "{\"region\":\"north\",\"audience\":\"VULNERABLE\",\"healthConditions\":[\"ASTHMA\",\"COPD\",\"ASTHMA\"]}";

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired EventRepository eventRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private User alice;
    private User bob;
    private User admin;
    private String aliceToken;
    private String bobToken;
    private String adminToken;

    @BeforeEach
    void cleanDatabaseAndCreateUsers() {
        jdbc.update("delete from warning_audit_log");
        jdbc.update("delete from warnings");
        jdbc.update("delete from audit_log"); // append-only in Postgres; H2 test copy has no trigger
        jdbc.update("delete from events");
        jdbc.update("delete from user_health_conditions");
        jdbc.update("delete from users");
        alice = userRepository.save(new User("alice", passwordEncoder.encode("alice-pw"), "user"));
        bob = userRepository.save(new User("bob", passwordEncoder.encode("bob-pw"), "user"));
        admin = userRepository.save(new User("root", passwordEncoder.encode("root-pw"), "admin"));
        aliceToken = jwtService.generateToken("alice", "user");
        bobToken = jwtService.generateToken("bob", "user");
        adminToken = jwtService.generateToken("root", "admin");
    }

    /** The test database is shared with other test classes, whose cleanup does `delete from users`. */
    @AfterEach
    void removeProfileRows() {
        jdbc.update("delete from user_health_conditions");
    }

    private ResultActions putProfile(String token, String body) throws Exception {
        return mockMvc.perform(put("/me/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getProfile(String token) throws Exception {
        return mockMvc.perform(get("/me/profile").header("Authorization", "Bearer " + token));
    }

    // ---- CG-6 ----

    @Test
    void newUser_hasEmptyProfile() throws Exception {
        getProfile(aliceToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.region").doesNotExist())
                .andExpect(jsonPath("$.healthConditions", hasSize(0)));
    }

    @Test
    void update_thenGet_returnsTheSavedProfile() throws Exception {
        putProfile(aliceToken, PROFILE).andExpect(status().isOk());

        getProfile(aliceToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("north"))
                .andExpect(jsonPath("$.audience").value("VULNERABLE"))
                .andExpect(jsonPath("$.healthConditions", hasSize(2)))
                .andExpect(jsonPath("$.healthConditions[0]").value("ASTHMA"))
                .andExpect(jsonPath("$.healthConditions[1]").value("COPD"));
    }

    @Test
    void update_replacesPreviousConditions() throws Exception {
        putProfile(aliceToken, PROFILE).andExpect(status().isOk());
        putProfile(aliceToken, "{\"region\":\"south\",\"audience\":\"GENERAL_PUBLIC\",\"healthConditions\":[]}")
                .andExpect(status().isOk());

        getProfile(aliceToken).andExpect(jsonPath("$.region").value("south"))
                .andExpect(jsonPath("$.healthConditions", hasSize(0)));
    }

    @Test
    void usersOnlySeeAndChangeTheirOwnProfile_idInBodyIsIgnored() throws Exception {
        putProfile(aliceToken, PROFILE).andExpect(status().isOk());
        // an attacker-supplied id in the body must not redirect the write
        putProfile(bobToken, "{\"id\":\"" + alice.getId() + "\",\"userId\":\"" + alice.getId()
                + "\",\"region\":\"west\",\"audience\":\"GENERAL_PUBLIC\",\"healthConditions\":[]}")
                .andExpect(status().isOk());

        getProfile(aliceToken).andExpect(jsonPath("$.region").value("north"));
        getProfile(bobToken).andExpect(jsonPath("$.region").value("west"));
    }

    @Test
    void unknownRegion_isBadRequest() throws Exception {
        putProfile(aliceToken, "{\"region\":\"mars\",\"audience\":\"VULNERABLE\",\"healthConditions\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("region")))
                .andExpect(jsonPath("$.message", containsString("mars")));
    }

    @Test
    void wrongCaseRegion_isBadRequest() throws Exception {
        putProfile(aliceToken, "{\"region\":\"North\",\"audience\":\"VULNERABLE\",\"healthConditions\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("North")));
    }

    @Test
    void missingRegion_isBadRequest() throws Exception {
        putProfile(aliceToken, "{\"audience\":\"VULNERABLE\",\"healthConditions\":[]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAudience_isBadRequest() throws Exception {
        putProfile(aliceToken, "{\"region\":\"north\",\"audience\":\"VIP\",\"healthConditions\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("audience")));
    }

    @Test
    void unknownCondition_isBadRequest_andNothingIsSaved() throws Exception {
        putProfile(aliceToken, "{\"region\":\"north\",\"audience\":\"VULNERABLE\",\"healthConditions\":[\"ASTHMA\",\"flu\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("healthConditions")));

        getProfile(aliceToken).andExpect(jsonPath("$.region").doesNotExist())
                .andExpect(jsonPath("$.healthConditions", hasSize(0)));
    }

    @Test
    void noToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/me/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/me/profile").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isUnauthorized());
    }
}

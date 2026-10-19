package com.cs203.healthwatch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WarningAuditEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void createUsers() {
        jdbc.update("delete from audit_log");
        jdbc.update("delete from events");
        jdbc.update("delete from users");
        userRepository.save(new User("admin", "hash", "admin"));
        userRepository.save(new User("viewer", "hash", "viewer"));
        adminToken = jwtService.generateToken("admin", "admin");
        viewerToken = jwtService.generateToken("viewer", "viewer");
    }

    private void insertEntry(UUID warningId, String action, Instant at) {
        jdbc.update("""
                INSERT INTO public.warning_audit_log (warning_id, actor, action, created_at)
                VALUES (?, 'system', ?, ?)
                """, warningId, action, Timestamp.from(at));
    }

    @Test
    void returnsEntriesOldestFirstAndBreaksTiesById() throws Exception {
        UUID w = UUID.randomUUID();
        Instant t = Instant.parse("2026-09-24T04:00:00Z");
        insertEntry(w, "THIRD", t.plusSeconds(60));
        insertEntry(w, "FIRST", t);
        insertEntry(w, "FIRST_TIE", t);

        mvc.perform(get("/warnings/" + w + "/audit").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("FIRST"))
                .andExpect(jsonPath("$[1].action").value("FIRST_TIE"))
                .andExpect(jsonPath("$[2].action").value("THIRD"));
    }

    @Test
    void unknownWarningReturnsEmptyList() throws Exception {
        mvc.perform(get("/warnings/" + UUID.randomUUID() + "/audit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(get("/warnings/" + UUID.randomUUID() + "/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminReturns403() throws Exception {
        mvc.perform(get("/warnings/" + UUID.randomUUID() + "/audit")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }
}
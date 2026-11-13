package com.cs203.healthwatch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdEventIds = new ArrayList<>();
    private final List<UUID> createdWarningIds = new ArrayList<>();
    private static final String TEST_PREFIX = "test-audit-";

    @BeforeEach
    void createUsers() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String adminName = TEST_PREFIX + "audit-admin-" + suffix;
        String viewerName = TEST_PREFIX +"audit-viewer-" + suffix;
        User admin = userRepository.save(new User(adminName, "hash", "admin"));
        User viewer = userRepository.save(new User(viewerName, "hash", "viewer"));
        createdUserIds.add(admin.getId());
        createdUserIds.add(viewer.getId());
        adminToken = jwtService.generateToken(admin.getId(), adminName, "admin");
        viewerToken = jwtService.generateToken(viewer.getId(), viewerName, "viewer");
    }

    @AfterEach
    void cleanUp() {
        // child rows first: warnings reference events
        createdWarningIds.forEach(id -> jdbc.update("delete from warnings where id = ?", id));
        createdEventIds.forEach(id -> jdbc.update("delete from events where id = ?", id));
        createdUserIds.forEach(id -> jdbc.update("delete from users where id = ?", id));
        createdWarningIds.clear();
        createdEventIds.clear();
        createdUserIds.clear();
    }

    private void insertEntry(UUID warningId, String action, Instant at) {
        jdbc.update("""
                INSERT INTO public.warning_audit_log (warning_id, actor, action, created_at)
                VALUES (?, 'system', ?, ?)
                """, warningId, action, Timestamp.from(at));
    }

    @Test
    void returnsEntriesOldestFirstAndBreaksTiesById() throws Exception {
        UUID eventId = jdbc.queryForObject("""
                insert into events (signal_type, region, detected_at, deviation, status, is_replay)
                values ('pm25', 'test-region', now(), 4.2, 'NEW', false) returning id
                """, UUID.class);
        createdEventIds.add(eventId);
        UUID w = jdbc.queryForObject("""
                insert into warnings (event_id, severity, warning_text)
                values (?, 'LOW', 'test draft') returning id
                """, UUID.class, eventId);
        createdWarningIds.add(w);

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
    void unknownWarningReturns404() throws Exception {
        mvc.perform(get("/warnings/" + UUID.randomUUID() + "/audit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
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
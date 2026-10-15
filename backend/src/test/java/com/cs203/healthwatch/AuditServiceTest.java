package com.cs203.healthwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cs203.healthwatch.service.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Deliberately NOT @Transactional: the test needs real commits and rollbacks.
@SpringBootTest
class AuditServiceTest {

    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // Adjust the columns if your events table needs others (check how EventStatusRollbackTest inserts one).
        eventId = jdbc.queryForObject("""
                insert into events (signal_type, region, detected_at, deviation, status, is_replay)
                values ('pm25', 'test-region', now(), 4.2, 'NEW', false)
                returning id
                """, UUID.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from events where id = ?", eventId);
        // warning_audit_log is append-only, so test rows stay; they use random warning ids and are harmless.
    }

    @Test
    void systemActionIsRecordedWithSystemActor() {
        UUID warningId = UUID.randomUUID();

        tx.executeWithoutResult(s ->
                audit.recordBySystem(warningId, "DRAFT_CREATED", null, Map.of("status", "PENDING_REVIEW")));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select actor, action, before is null as before_null, after->>'status' as status "
                        + "from warning_audit_log where warning_id = ?", warningId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("actor", "system")
                .containsEntry("action", "DRAFT_CREATED")
                .containsEntry("before_null", true)
                .containsEntry("status", "PENDING_REVIEW");
    }

    @Test
    void adminActionRecordsTheUserId() {
        UUID warningId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        tx.executeWithoutResult(s -> audit.recordByUser(warningId, adminId, "EDITED",
                Map.of("text", "old"), Map.of("text", "new")));

        String actor = jdbc.queryForObject(
                "select actor from warning_audit_log where warning_id = ?", String.class, warningId);
        assertThat(actor).isEqualTo(adminId.toString());
    }

    @Test
    void actionRollsBackWhenAuditWriteFails() {
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.update("update events set status = 'UNDER_REVIEW' where id = ?", eventId);
            // null action violates NOT NULL, so the audit insert fails
            audit.recordBySystem(UUID.randomUUID(), null, null, null);
        }));

        String status = jdbc.queryForObject("select status from events where id = ?", String.class, eventId);
        assertThat(status).isEqualTo("NEW");
    }

    @Test
    void recordingOutsideATransactionIsRejected() {
        assertThrows(IllegalTransactionStateException.class,
                () -> audit.recordBySystem(UUID.randomUUID(), "DRAFT_CREATED", null, null));
    }

    @Test
    void adminActionWithoutActorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s ->
                audit.recordByUser(UUID.randomUUID(), null, "EDITED", null, null)));
    }
}
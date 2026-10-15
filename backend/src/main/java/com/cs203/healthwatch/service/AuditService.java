package com.cs203.healthwatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    public static final String SYSTEM_ACTOR = "system";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Admin action: the actor is the user id taken from the token. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordByUser(UUID warningId, UUID actorId, String action, Object before, Object after) {
        if (actorId == null) {
            throw new IllegalArgumentException("An audited admin action needs an actor");
        }
        insert(warningId, actorId.toString(), action, before, after);
    }

    /** System action (for example the AI drafting a warning): no token, so the actor is 'system'. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBySystem(UUID warningId, String action, Object before, Object after) {
        insert(warningId, SYSTEM_ACTOR, action, before, after);
    }

    private void insert(UUID warningId, String actor, String action, Object before, Object after) {
        jdbc.update("""
                INSERT INTO public.warning_audit_log (warning_id, actor, action, before, after)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
                """,
                warningId, actor, action, toJson(before), toJson(after));
    }

    private String toJson(Object o) {
        try {
            return o == null ? null : mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise audit snapshot", e);
        }
    }
}
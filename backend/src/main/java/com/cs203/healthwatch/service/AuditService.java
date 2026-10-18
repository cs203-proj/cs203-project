package com.cs203.healthwatch.service;

import com.cs203.healthwatch.dto.WarningAuditEntryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.*;
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

        /** Full action history of one warning, oldest first. Ties on created_at are broken by id. */
    @Transactional(readOnly = true)
    public List<WarningAuditEntryResponse> trail(UUID warningId) {
        // TODO(CG-21): once public.warnings exists, throw a 404 here when the warning does not exist.
        return jdbc.query("""
                        SELECT id, actor, action, before::text AS before, after::text AS after, created_at
                        FROM public.warning_audit_log
                        WHERE warning_id = ?
                        ORDER BY created_at, id
                        """,
                (rs, i) -> new WarningAuditEntryResponse(
                        rs.getLong("id"),
                        rs.getString("actor"),
                        rs.getString("action"),
                        readJson(rs.getString("before")),
                        readJson(rs.getString("after")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                warningId);
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit snapshot is not valid JSON", e);
        }
    }
}
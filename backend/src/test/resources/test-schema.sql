-- Tables the H2 test profile cannot build from entities because they are written with plain SQL.
-- Mirrors V6__create_warning_audit_log.sql without the Postgres-only parts (RLS, append-only triggers).
-- Plain text, not H2's JSON type: H2 would store '{...}'::json as a JSON string, Postgres stores the object.
create domain if not exists jsonb as varchar;

create table if not exists warning_audit_log (
    id          bigint generated always as identity primary key,
    warning_id  uuid        not null,
    actor       text        not null,
    action      text        not null,
    before      jsonb,
    after       jsonb,
    created_at  timestamp with time zone not null default current_timestamp
);

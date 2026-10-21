-- V7: warnings table (CG-29 sub-task). Minimal columns from the Sprint 2 acceptance criteria;
-- CG-21 / CG-28 / CG-36 add their own columns in later migrations as needed.

create table warnings (
    id            uuid primary key default gen_random_uuid(),
    event_id      uuid not null references events(id),
    audience      text not null default 'GENERAL_PUBLIC',
    severity      text not null,
    status        text not null default 'PENDING_REVIEW',
    warning_text  text not null,                 -- AI draft, never overwritten (CG-21)
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint chk_warnings_severity check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint chk_warnings_status check (status in ('PENDING_REVIEW', 'APPROVED', 'SUPPRESSED'))
);

-- Review queue (GET /warnings?status=PENDING_REVIEW): filter by status, then oldest first within a severity (CG-29)
create index idx_warnings_status_created on warnings (status, created_at, id);
create index idx_warnings_event on warnings (event_id);

-- warning_text is the original AI draft (CG-21: "never overwritten"). Enforced in the database, like audit_log
-- in V3, so no code path or direct SQL can change it. Other columns (status, severity) stay updatable.
create or replace function warnings_reject_text_change() returns trigger as $$
begin
    raise exception 'warnings.warning_text is the original AI draft and cannot be changed';
end;
$$ language plpgsql;

create trigger warnings_warning_text_immutable
    before update of warning_text on warnings
    for each row
    when (old.warning_text is distinct from new.warning_text)
    execute function warnings_reject_text_change();

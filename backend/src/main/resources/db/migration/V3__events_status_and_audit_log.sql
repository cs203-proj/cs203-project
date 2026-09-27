-- V3: event lifecycle (CG-14, CG-35) and an append-only audit log (CG-83)

-- events: status now holds EventStatus names (NEW, UNDER_REVIEW, CONFIRMED, DISMISSED)
update events set status = 'NEW' where status = 'open';
alter table events alter column status set default 'NEW';
alter table events add constraint chk_events_status
    check (status in ('NEW', 'UNDER_REVIEW', 'CONFIRMED', 'DISMISSED'));

-- events: bookkeeping columns expected by BaseEntity
alter table events add column created_at timestamptz not null default now();
alter table events add column updated_at timestamptz not null default now();

-- events: replay flag used by DetectedEvent (CG-13), so demo replays are never mistaken for live detections
alter table events add column is_replay boolean not null default false;

-- events list is sorted by deviation, largest first, with a stable tie-break (GET /events)
create index idx_events_deviation on events (deviation desc nulls last, detected_at desc, id);

-- audit_log: only valid statuses may be recorded
alter table audit_log add constraint chk_audit_log_old_status
    check (old_status is null or old_status in ('NEW', 'UNDER_REVIEW', 'CONFIRMED', 'DISMISSED'));
alter table audit_log add constraint chk_audit_log_new_status
    check (new_status in ('NEW', 'UNDER_REVIEW', 'CONFIRMED', 'DISMISSED'));
create index idx_audit_log_event_time on audit_log (event_id, changed_at, id);

-- audit_log is append-only: block UPDATE, DELETE and TRUNCATE for every database user.
-- A trigger is used instead of REVOKE because the app connects as the table owner (it runs Flyway),
-- and an owner can always re-grant privileges to itself.
create or replace function audit_log_reject_change() returns trigger as $$
begin
    raise exception 'audit_log is append-only: % is not allowed', tg_op;
end;
$$ language plpgsql;

create trigger audit_log_no_update_or_delete
    before update or delete on audit_log
    for each row execute function audit_log_reject_change();

create trigger audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_reject_change();

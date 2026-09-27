-- S1-6: columns the DetectedEvent entity needs.
-- IF NOT EXISTS makes this safe whether or not they already exist.
alter table events add column if not exists is_replay  boolean     not null default false;
alter table events add column if not exists created_at timestamptz not null default now();
alter table events add column if not exists updated_at timestamptz not null default now();

-- V1 defaulted status to 'open', which isn't a valid EventStatus; the app always sets it
alter table events alter column status drop default;

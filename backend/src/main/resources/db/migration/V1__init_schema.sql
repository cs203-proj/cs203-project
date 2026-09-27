-- V1__init_schema.sql, not final

-- users
create table users (
    id uuid primary key default gen_random_uuid(),
    username text unique not null,
    password_hash text not null,
    role text not null default 'admin',
    created_at timestamptz not null default now()
);

-- data_sources (placeholder)
create table data_sources (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    type text,
    created_at timestamptz not null default now()
);

-- readings (placeholder)
create table readings (
    id bigserial primary key,
    source_id uuid not null references data_sources(id),
    region text not null,
    value double precision not null,
    observed_at timestamptz not null,
    ingested_at timestamptz not null default now(),
    is_malformed boolean not null default false,
    is_synthetic boolean not null default false,
    raw_payload jsonb,
    unique (source_id, region, observed_at)
);

-- events (placeholder)
create table events (
    id uuid primary key default gen_random_uuid(),
    signal_type text not null,
    region text not null,
    detected_at timestamptz not null default now(),
    deviation double precision,
    status text not null default 'open'
);

-- baselines (placeholder)
create table baselines (
    id uuid primary key default gen_random_uuid(),
    source_id uuid not null references data_sources(id),
    region text not null,
    baseline_value double precision not null,
    computed_at timestamptz not null default now()
);

-- audit_log (placeholder)
create table audit_log (
    id bigserial primary key,
    event_id uuid not null references events(id),
    actor_id uuid not null references users(id),
    old_status text,
    new_status text not null,
    changed_at timestamptz not null default now()
);

-- helpful indexes
create index idx_readings_source_region_time on readings (source_id, region, observed_at);
create index idx_events_status on events (status);
create index idx_audit_log_event on audit_log (event_id);
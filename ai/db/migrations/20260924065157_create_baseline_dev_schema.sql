-- baseline_dev workspace for S1-5: schema + readings + baselines.
-- Captured from the live Supabase definition (columns, order, nullability, defaults, constraints, comment);
-- applied live before the other migrations in this folder.
--
-- No grants and no RLS here, matching live: RLS is disabled (Supabase advisory: rls_disabled) and
-- access for service_role comes from 20260925124919_baseline_dev_grant_service_role.sql.
-- gen_random_uuid() is built into Postgres 13+ (Supabase runs 17).

create schema if not exists baseline_dev;

comment on schema baseline_dev is
  'S1-5 dev workspace — synthetic-data baseline work, ahead of public.readings/public.baselines being wired up. Migrate to public schema once source_id/signal_type shape and public.baselines columns are confirmed with team.';

create table baseline_dev.readings (
  id           uuid             not null default gen_random_uuid(),
  region       text             not null,
  signal_type  text             not null,
  value        double precision,
  observed_at  timestamptz      not null,
  ingested_at  timestamptz      default now(),
  is_malformed boolean          default false,
  is_synthetic boolean          default false,
  source_id    text,
  raw_payload  jsonb,
  primary key (id),                              -- readings_pkey
  unique (region, signal_type, observed_at)      -- readings_region_signal_type_observed_at_key
);

create table baseline_dev.baselines (
  id           uuid             not null default gen_random_uuid(),
  region       text             not null,
  signal_type  text             not null,
  median       double precision,
  mad          double precision,
  window_start timestamptz,
  window_end   timestamptz,
  computed_at  timestamptz      default now(),
  primary key (id),                                          -- baselines_pkey
  unique (region, signal_type, window_start, window_end)     -- baselines_region_signal_type_window_start_window_end_key
);

-- SUPERSEDED by 20260925130658_baseline_dev_drop_redundant_baselines_index.sql (kept unchanged below
-- because it is already recorded in Supabase's migration history). It was redundant from the start:
-- the schema migration already creates an equivalent UNIQUE constraint on these four columns.
-- Idempotent upserts for POST /baselines/recompute (CG-65). baseline_dev only; public untouched.
create unique index if not exists baselines_region_signal_window_uidx
  on baseline_dev.baselines (region, signal_type, window_start, window_end);

-- Supersedes 20260924072413_baseline_dev_baselines_unique_window: that index duplicated the UNIQUE
-- constraint from the schema migration (baselines_region_signal_type_window_start_window_end_key),
-- which already backs ON CONFLICT (region, signal_type, window_start, window_end).
drop index if exists baseline_dev.baselines_region_signal_window_uidx;

-- service_role only; anon/authenticated intentionally NOT granted (RLS is disabled on these tables).
-- Without this, the API fails with 42501 "permission denied for schema baseline_dev" even when the
-- schema is exposed in Supabase's API settings (exposing a schema does not grant privileges).
grant usage on schema baseline_dev to service_role;
grant select on baseline_dev.readings to service_role;
grant select, insert, update on baseline_dev.baselines to service_role;

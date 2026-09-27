# baseline_dev migrations (Supabase)

Apply in filename order; the timestamp prefix is the version recorded in Supabase's migration history.
Together they rebuild `baseline_dev` from an empty database:

1. `20260924065157_create_baseline_dev_schema.sql` - schema, `readings`, `baselines`
2. `20260924072413_baseline_dev_baselines_unique_window.sql` - redundant unique index (superseded by 4)
3. `20260925124919_baseline_dev_grant_service_role.sql` - `service_role` grants
4. `20260925130658_baseline_dev_drop_redundant_baselines_index.sql` - drops the index from 2

Deliberately NOT in `backend/src/main/resources/db/migration`: Spring's Flyway runs that folder against the
local/CI Postgres, where the `service_role` role does not exist.

Not covered by these files (fresh Supabase project checklist):
- Add `baseline_dev` under Settings -> API -> Exposed schemas (a dashboard setting, not SQL).
- The ~2,400 synthetic `readings` rows are data, not schema; no seed script is in the repo.
- RLS is disabled on both tables (as live). Enable it and add policies before exposing to anon/authenticated.

Note: migration 2 is kept (not deleted) because it is already in Supabase's migration history; migration 4
undoes it. Upserts rely on the UNIQUE constraint from migration 1, the only unique key on the four window columns.

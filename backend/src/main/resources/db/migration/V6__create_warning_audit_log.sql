-- V6__create_warning_audit_log.sql
CREATE TABLE public.warning_audit_log (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warning_id  uuid        NOT NULL,
  actor       text        NOT NULL,   -- user id from the token, or 'system'
  action      text        NOT NULL,
  before      jsonb,                  -- null on creation
  after       jsonb,
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX warning_audit_log_warning_idx
  ON public.warning_audit_log (warning_id, created_at, id);

ALTER TABLE public.warning_audit_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.warning_audit_log FROM anon, authenticated;

CREATE FUNCTION public.warning_audit_log_reject_change() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'warning_audit_log is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER warning_audit_log_no_update_or_delete
  BEFORE UPDATE OR DELETE ON public.warning_audit_log
  FOR EACH ROW EXECUTE FUNCTION public.warning_audit_log_reject_change();

CREATE TRIGGER warning_audit_log_no_truncate
  BEFORE TRUNCATE ON public.warning_audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION public.warning_audit_log_reject_change();
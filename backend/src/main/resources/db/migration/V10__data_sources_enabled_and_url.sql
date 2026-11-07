-- CG-58: admins can disable a source (ingestion and backfill skip it) and set its feed URL.
-- Existing rows stay enabled. url is nullable: null means "use pm.url from config".
alter table data_sources add column enabled boolean not null default true;
alter table data_sources add column url text;

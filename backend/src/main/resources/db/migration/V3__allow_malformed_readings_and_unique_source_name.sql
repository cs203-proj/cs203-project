-- Malformed readings can have null observed_at.
alter table readings alter column value drop not null;
alter table readings alter column observed_at drop not null;

create unique index if not exists uq_data_sources_name on data_sources (name);
-- V12: account anonymisation (CG-7). The row and id are kept so audit_log stays valid.

alter table users add column deleted_at timestamptz;

-- anonymised accounts have no password
alter table users alter column password_hash drop not null;

-- a deleted account must carry no personal data
alter table users add constraint chk_users_deleted_is_wiped
    check (deleted_at is null or (password_hash is null and region is null and audience is null));

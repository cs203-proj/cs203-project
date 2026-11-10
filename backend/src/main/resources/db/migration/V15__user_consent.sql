-- V15: consent recorded at sign-up (CG-8).
--
-- An end user has to accept the health-data consent notice before an account is created, and we keep which version
-- they accepted and when. Keeping the version (rather than just a boolean) means that if the notice is reworded we
-- can tell who agreed to which wording, and ask again only from the people who need to.
--
-- Both nullable: the seeded admin account predates consent and is not an end user.

alter table users
    add column consent_version text,
    add column consented_at    timestamptz;

comment on column users.consent_version is
    'Version of the consent notice the user accepted at sign-up (CG-8). Null for accounts created before consent.';
comment on column users.consented_at is
    'When the user accepted that version of the consent notice (CG-8).';

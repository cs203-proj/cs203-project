-- V12: columns the end-user warning feed (CG-22 / CG-23) reads.
--
-- approved_at  : set by the approve action (CG-28) when status becomes APPROVED. "Active" in GET /me/warnings means
--                approved_at is inside the configured window (default 24h), since expiry (CG-31) is descoped.
-- edited_text  : the admin's edited wording (CG-28). warning_text stays the original AI draft (immutable, see V7);
--                end users are shown edited_text when it is set, otherwise warning_text.
-- ai_line      : optional AI-written personalised sentence from the per-audience drafter (CG-154). Always shown to
--                end users labelled as AI-generated, separately from the code-generated "why this applies" line.
--
-- All nullable: existing rows keep working, and nothing here changes how the admin review queue behaves.
alter table warnings add column approved_at timestamptz;
alter table warnings add column edited_text text;
alter table warnings add column ai_line text;

-- Feed query: APPROVED warnings for one audience, newest approval first.
create index idx_warnings_feed on warnings (status, audience, approved_at);

-- V14: how many times the AI has been asked to explain an event (CG-152).
-- V13 is taken by the account anonymisation PR (#37), so this is V14.
--
-- A FAILED explanation is retried on a schedule; the retry updates the same row (event_id stays unique), so the
-- row needs a counter to stop retrying after the configured maximum.

alter table event_explanations
    add column attempts int not null default 1;

-- Every existing row was produced by exactly one attempt.
update event_explanations set attempts = 1;

alter table event_explanations
    add constraint chk_event_explanations_attempts check (attempts >= 1);

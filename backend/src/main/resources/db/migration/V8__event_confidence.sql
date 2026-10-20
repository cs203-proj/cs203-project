-- V8: confidence score stored with each investigated event (CG-16).
-- Inputs and score are kept together so the same inputs always give the same score; formula_version says which
-- formula (docs/confidence-score.md) produced it. All columns are nullable: events created before V5 have no score.
-- The deviation input is the existing events.deviation column.
alter table events add column confidence_score double precision;
alter table events add column confidence_level text;
alter table events add column confidence_formula_version integer;
alter table events add column confidence_staleness_seconds bigint;
alter table events add column confidence_reading_count integer;
alter table events add column confidence_source_agreement double precision; -- null until a second source exists

alter table events add constraint chk_events_confidence_score
    check (confidence_score is null or (confidence_score >= 0 and confidence_score <= 1));
alter table events add constraint chk_events_confidence_level
    check (confidence_level is null or confidence_level in ('LOW', 'MEDIUM', 'HIGH'));
alter table events add constraint chk_events_confidence_agreement
    check (confidence_source_agreement is null
           or (confidence_source_agreement >= 0 and confidence_source_agreement <= 1));

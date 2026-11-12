-- V16: how widely the spike shows up across regions, for the AI explanation (CG-171).
--
-- scope is computed in code (SpatialScopeCalculator), never by the AI. regions_above and regions_compared are JSON
-- arrays of region codes (compared = regions that took part in the comparison), jsonb like evidence/assumptions in V9. Both are null on rows written before this migration; readers treat null as
-- UNKNOWN, so no backfill.

alter table event_explanations
    add column scope varchar(20),
    add column regions_above jsonb,
    add column regions_compared jsonb;

alter table event_explanations
    add constraint chk_event_explanations_scope
        check (scope is null or scope in ('LOCAL', 'MULTI_REGION', 'ISLAND_WIDE', 'UNKNOWN'));

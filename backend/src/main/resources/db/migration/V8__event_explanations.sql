-- V8: AI explanation for each detected event (CG-135).
-- Numbered V8 because V5/V6 (CG-36-audit-trail) and V7 (CG-29-warnings-table) are already taken on other branches.
--
-- One row per event. A failed AI call is stored too (status FAILED with the error), so an admin can see that an
-- explanation was attempted and why it is missing. The event row itself is never changed by this feature.

create table event_explanations (
    id                uuid primary key default gen_random_uuid(),
    event_id          uuid not null unique references events(id),
    status            text not null,
    assessment        text,                      -- GENUINE_TREND, LIKELY_NOISE or UNCERTAIN; null when FAILED
    likely_cause      text,
    explanation       text,
    evidence          jsonb,                     -- JSON array of strings: the key facts the explanation rests on
    assumptions       jsonb,                     -- JSON array of strings: what the explanation takes for granted
    data_complete     boolean not null,          -- computed in code, not by the AI
    data_quality_note text not null,             -- e.g. "Based on partial data: 4 of 6 expected hourly readings..."
    model             text,
    error             text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint chk_event_explanations_status check (status in ('OK', 'FAILED')),
    constraint chk_event_explanations_assessment
        check (assessment is null or assessment in ('GENUINE_TREND', 'LIKELY_NOISE', 'UNCERTAIN'))
);

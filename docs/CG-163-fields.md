# CG-163: AI fields in the review queue (`GET /warnings`)

Each item has a new object `ai` (admin triage). Approve/edit/downgrade/suppress and `GET /events/{id}/warnings` return `ai: null`.
`aiLine` is separate: per-audience text for end users, not for this screen.

| `ai.` field | Type | Notes |
|---|---|---|
| `status` | `OK` \| `UNAVAILABLE` | Always present in the queue |
| `assessment` | `GENUINE_TREND` \| `LIKELY_NOISE` \| `UNCERTAIN` \| null | null when UNAVAILABLE |
| `likelyCause` | string \| null | null when UNAVAILABLE |
| `confidenceScore` | number 0.0–1.0 \| null | null on events scored before confidence existed |
| `confidenceLevel` | `LOW` (<0.40) \| `MEDIUM` \| `HIGH` (≥0.70) \| null | null with the score |
| `scope` | `LOCAL` \| `MULTI_REGION` \| `ISLAND_WIDE` \| `UNKNOWN` | CG-164. Computed by the system, not the AI. Always present; `UNKNOWN` when it could not be computed or for older rows. Kept even when UNAVAILABLE |
| `regionsAbove` | string[] | Regions above the detection threshold; `[]` when UNKNOWN |
| `regionsCompared` | string[] | Regions that took part in the comparison; `[]` when UNKNOWN |
| `scopeSummary` | string \| null | The AI's plain-words account of the scope; null when UNAVAILABLE or for older rows |

**UNAVAILABLE** = the AI explanation FAILED or does not exist yet. Show "AI summary unavailable"; confidence can still be set, so show it if present.
Not an error: the list always returns 200. Order is unchanged (severity, then oldest first).
Full explanation text and evidence stay on `GET /events/{id}/explanation`. Field names await frontend sign-off.

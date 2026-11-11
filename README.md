# cs203-project

Public health surveillance and early warning system — HealthWatch.

## Prerequisites

Install these once per machine:

- **Docker Desktop** — runs Postgres for local dev. https://www.docker.com/products/docker-desktop/
- **JDK 21 required (newer JDKs break Lombok)** — to build/run the backend. (Maven itself is *not* required — the project uses the Maven Wrapper, which downloads Maven automatically.)

You do **not** need to install Postgres or Maven yourself.

## First-time setup

```bash
git clone <this-repo-url>
cd cs203-project
```

That's it — there's no separate install step. The steps below (start DB, run backend) are also what you run every time you come back to work on the project.

## Running the backend

1. **Start Postgres** (from the repo root):
   ```bash
   docker compose up -d
   ```
   This starts a `healthwatch-postgres` container with a database matching the backend's default config (db `healthwatch`, user/password `healthwatch`/`healthwatch`). Data persists in a Docker volume across restarts.

2. **Run the backend**:
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
   (On Windows, use `mvnw.cmd` instead of `./mvnw`.) First run will download Maven + all dependencies, so it'll be slower than subsequent runs.

3. **Verify it's working** — in another terminal:
   ```bash
   curl http://localhost:8080/actuator/health
   ```
   Should return `{"status":"UP"}`.

## Notes

- Visiting `http://localhost:8080/` directly will prompt for a username/password — that's Spring Security's default login (`user` / a random password printed in the backend's console log at startup). This goes away once real auth is added; it's not the app's actual login system yet.
- API docs (once endpoints exist): `http://localhost:8080/swagger-ui.html`.
- To stop Postgres: `docker compose down` (add `-v` to also wipe the database data).
- `frontend/` is not scaffolded yet.

## Events and audit trail

All `/events` routes need an admin bearer token (`POST /auth/login`, then click **Authorize** in Swagger UI).
No token or an invalid token gives `401`; a valid non-admin token gives `403`.

| Endpoint | What it does |
| --- | --- |
| `GET /events?status=&limit=&offset=` | Flagged events, largest deviation first (events without a deviation last; ties broken by newest, then id). `status` is optional and case-insensitive; unknown values give `400`. `limit` defaults to 50 (max 200), `offset` to 0. |
| `PATCH /events/{id}/status` | Body `{"status": "UNDER_REVIEW"}`. Moves the event to a new status and writes one audit row in the same transaction. Returns the updated event. |
| `GET /events/{id}/audit` | Every status change on the event, oldest first, with actor, time, old and new status. Unknown event gives `404`. |

### Status lifecycle

| From | Allowed to |
| --- | --- |
| `NEW` | `UNDER_REVIEW`, `DISMISSED` |
| `UNDER_REVIEW` | `CONFIRMED`, `DISMISSED` |
| `DISMISSED` | `UNDER_REVIEW` (reopen, any admin) |
| `CONFIRMED` | none (final) |

- Every event starts as `NEW`.
- Any other move, including setting the status it already has, is rejected with `400`. The message names the current and requested status.
- `NEW → DISMISSED` lets an admin close an obvious false alarm without reviewing it first.
- A dismissed event can be reopened to `UNDER_REVIEW` but not straight back to `NEW`, so the trail always shows it was looked at.

### Audit history is immutable

- The actor on each audit row is always the user in the bearer token. The request body cannot set it; an `actor` field sent in the body is ignored.
- A status change and its audit row are written in one transaction. If either fails, neither is kept.
- Audit rows can be added and read, never changed or deleted:
  - The API has no route that edits or removes them.
  - `AuditLogRepository` only has `save` and a finder, with no update or delete methods.
  - The entity's columns are non-updatable.
  - A database trigger (migration `V3`) rejects every `UPDATE`, `DELETE` and `TRUNCATE` on `audit_log`, even from `psql`.
- The trigger is used instead of `REVOKE` because the app connects as the table owner (it runs Flyway), and an owner can always grant privileges back to itself.
- To wipe a local dev database, use `docker compose down -v`.

## Detection

A reading is scored against the baseline for its region and signal with a robust z-score:

```
z = (reading - median) / scaledMad
```

`scaledMad` is the median absolute deviation already multiplied by 1.4826, so it is comparable to a standard deviation. An event is created only when the last N readings all score above the threshold, are valid numbers, and are no further apart than the maximum gap. The values are set in `application.yml` (`z-threshold: 3.0`, `consecutive-readings: 3`, `max-gap: 90m`).

The score of the worst reading becomes the event's `deviation`.

### Zero-spread guard (CG-137)

If a baseline's history is perfectly flat, its `scaledMad` is `0`, and dividing by it would give an infinite or `NaN` deviation. That would break the confidence score (CG-16), which uses deviation size as an input.

- `BaselineSnapshot.hasUsableSpread()` is `false` when the median or `scaledMad` is `NaN` or infinite, or when `scaledMad` is `0` or negative.
- When it is `false`, `DeviationDetector.detect()` returns no event and never divides. No infinite or `NaN` deviation can be produced.
- Known trade-off: after a perfectly flat history, a real spike raises no alert until the baseline has some spread.
- Covered by `DeviationDetectorTest`: a constant series with `scaledMad = 0`, a `NaN` or infinite `scaledMad`, and a check that every deviation produced is a finite number.

## Warning drafts (CG-21)

Moving an event to `CONFIRMED` drafts one warning for the general public, in the same transaction as the status
change. If the draft cannot be saved, the confirm is rolled back too. Drafts start as `PENDING_REVIEW`; nothing is
sent to anyone.

| Endpoint | What it does |
| --- | --- |
| `GET /events/{id}/warnings` | Warnings drafted from the event, oldest first. Empty before the event is confirmed; unknown event gives `404`. |

Each draft says what happened, the confidence level, and one recommended action. Severity comes from the event's
robust z-score (detection only fires above 3):

| z-score | Severity | Recommended action |
| --- | --- | --- |
| below 5 | `LOW` | Check air quality updates before planning long outdoor activities today. |
| 5 to below 8 | `MEDIUM` | Cut down on prolonged or strenuous outdoor activity, especially with heart or lung conditions. |
| 8 to below 12 | `HIGH` | Avoid strenuous outdoor activity and keep windows closed. |
| 12 and above | `CRITICAL` | Stay indoors where possible, keep windows closed, wear an N95 mask outside. |

- `warningText` is the original draft. The column is not updatable, so admin edits (CG-28) must be stored alongside it.
- Drafts are built from a fixed template, so the same event always gives the same text and the health advice is
  never invented per event.
- The confidence line is the event's confidence level (CG-16). Events scored before confidence existed say
  "Confidence: not yet assessed."

### Per-audience drafts and the AI line (CG-154)

Confirming an event drafts one warning per audience, all `PENDING_REVIEW`, in the same transaction as the status
change: `GENERAL_PUBLIC` (the text above, unchanged), `VULNERABLE` and `HEALTHCARE_WORKER`. The audience codes are the
same as the profile's (CG-6).

- All three share the event's severity and confidence. Each has its own fixed "What you should do" advice, based on the
  MOH/NEA health advisory. The tables, the source and the severity mapping are in
  [docs/warning-advice.md](docs/warning-advice.md).
- Each draft gets its own `DRAFT_CREATED` audit entry and is its own row, so it is reviewed on its own in the queue.
- At most one draft per event per audience. Events confirmed before CG-154 keep their single general-public draft
  (`CONFIRMED` is final, so they are never re-drafted).

Each draft can also get an AI-written "why this matters for this audience" line in `warnings.ai_line` (`aiLine` on
`GET /events/{id}/warnings` and `GET /warnings`, and labelled "AI-generated" on `GET /me/warnings`):

- Built from the event's CG-135 explanation (assessment and likely cause) plus the audience. The AI is never sent the
  advice and is told never to give any. Strict JSON schema output, validated like CG-135.
- Uses the same Groq key and model as AI explanations. Turn it off with `AI_LINE_ENABLED=false`; with no key, nothing
  is attempted.
- Runs in the background right after the confirm commits, so confirming never waits on or fails because of the AI. If
  the explanation is missing or `FAILED`, the AI call fails, or the reply is blank or over 400 characters, the draft
  stays without a line and the reason is logged.
- Only written while the warning is still `PENDING_REVIEW` and has no line yet, so an admin always sees it before
  approving. Recorded in the warning's audit trail as `AI_LINE_ADDED` by `system`.
- One row is shared by everyone in the audience, so no user's profile or health conditions are ever sent to the AI.

## Profile and account deletion (CG-6, CG-7)

An end user has a profile that drives per-audience warnings (CG-154) and `GET /me/warnings` (CG-22).

| Endpoint | What it does |
|----------|--------------|
| `GET /me/profile` | The caller's `region`, `audience` and `healthConditions`. Empty until first set. |
| `PUT /me/profile` | Replaces the profile. Unknown codes give `400`; the message names the field and value. |
| `DELETE /me` | Anonymises the caller's account (see below). `204` on success; admin accounts get `403`. |

All `/me` routes need a bearer token (any role). The user is always the one in the token; there is no user id in any path or body. No token or an invalid token gives `401`. Admins can view and edit a profile but cannot delete their account through this endpoint.

### Frozen codes (shared with CG-154 and CG-22)

- `region`: `central`, `north`, `south`, `east`, `west` (lowercase, same as `pm.regions`).
- `audience`: `GENERAL_PUBLIC`, `VULNERABLE`, `HEALTHCARE_WORKER` (uppercase, same as `warnings.audience`).
- `healthConditions`: a list of codes from `HealthCondition.java`; never free text. Duplicates are ignored.
- Case matters: `North` is rejected. Changing a code needs CG-6, CG-154 and CG-22 to agree.

### Account deletion anonymises instead of hard-deleting

`audit_log` is append-only (trigger in `V3`) and references `users` as the actor. Deleting the user row would either break that foreign key or force changes to audit history, so `DELETE /me` keeps the row and its id and removes the personal data:

- `username` becomes `deleted-<random uuid>`, not derived from the old name, so the old name is free again.
- `password_hash`, `region`, `audience` and all health conditions are cleared; `deleted_at` is set. All of this happens in one transaction.
- No `audit_log` row is touched. Audit trails show the anonymised username for that actor.
- The old credentials cannot log in, and tokens for the account stop working at once: `JwtAuthFilter` ignores deleted users, so any `/me` call, including `GET /me/warnings`, returns `401`.
- Tokens identify the account by its id (`uid` claim), and `JwtAuthFilter` looks the account up by id, never by username. This matters because the old name is free again: a token issued to a deleted `alice` can never act as a new account that is also called `alice`. Tokens without a `uid` are rejected, so everyone logs in again once after this change is deployed.
- Admin accounts cannot be deleted through this endpoint (`403`).
- A database check (migration `V13`) makes it impossible to have `deleted_at` set while a password, region or audience remains.
- `users` has no consent columns yet. Whoever adds them must also clear them in `ProfileService.deleteAccount`.
- Any future query that lists users (for example by audience) should filter `deleted_at IS NULL`.

## My warnings (CG-22, CG-23)

`GET /me/warnings` is the end user's feed. Any valid token works; no token gives `401`. The user comes from the token.

A warning is returned only if all of these hold:

- its status is `APPROVED` (pending and suppressed warnings are never returned, including downgraded-then-suppressed ones);
- its event's `region` equals the user's region and its `audience` equals the user's audience;
- it was approved within `app.warnings.feed.active-window` (default `24h`, env `WARNING_ACTIVE_WINDOW`). Expiry (CG-31) is
  descoped, so this window is what retires a warning.

Order: most severe first, then newest approval first. A user with no region or no audience gets `200`, an empty list and a
message to complete their profile.

| Field | Source |
| --- | --- |
| `text` | `warnings.edited_text` if an admin edited the warning, otherwise the original draft `warning_text` |
| `confidence.level` / `confidence.meaning` | The event's CG-16 band (`LOW` / `MEDIUM` / `HIGH`) and a fixed plain-language sentence for it. `level` is null and the meaning says "not been assessed" for events scored before CG-16 |
| `whyThisApplies` | Built in code from the user's profile, e.g. "You are in the east region and receive advice for vulnerable groups." Health conditions are named only for the vulnerable audience, and only the ones this user entered |
| `aiLine` | `warnings.ai_line` (CG-154), always `{"label": "AI-generated"}`; null if there is none |
| `dataQualityNote` | The CG-135 note, only when that explanation's `dataComplete` is false (stale or partial data); otherwise null |

Migration `V12` adds `warnings.approved_at`, `warnings.edited_text` and `warnings.ai_line`. The approve and edit actions
(CG-28) must set `approved_at` when status becomes `APPROVED` and write the admin's wording to `edited_text`, never to
`warning_text`. CG-154 writes `ai_line`.

## Project structure

- `backend/` — Spring Boot 3.3 (Java 21) API: web, JPA, Postgres, Flyway, Spring Security, JWT (jjwt), OpenAPI/Swagger.
- `frontend/` — not yet started.
- `docker-compose.yml` — local Postgres for dev.

## Deployment

Live backend: https://cs203-project.onrender.com

- Health check: https://cs203-project.onrender.com/actuator/health
- API docs: https://cs203-project.onrender.com/swagger-ui.html

Hosted on Render, deployed automatically from `main` using `backend/Dockerfile`.
The database is Supabase Postgres (Session pooler connection).

Note: the Render free plan sleeps after ~15 minutes of inactivity, so the first
request after idling can take up to a minute.

### Environment variables

Set in Render under Environment; see `.env.example` for the full list.
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD` come from Supabase (Connect → Session
pooler); `JWT_SECRET` is a long random string.

## Confidence score

Each detected event carries a computed confidence (score 0 to 1 and Low / Medium / High) in the `confidence` field of
the `/events` responses.

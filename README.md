# cs203-project

Public health surveillance and early warning system — HealthWatch.

## Prerequisites

Install these once per machine:

- **Docker Desktop** — runs Postgres for local dev. https://www.docker.com/products/docker-desktop/
- **JDK 21+** — required to build/run the backend. (Maven itself is *not* required — the project uses the Maven Wrapper, which downloads Maven automatically.)

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

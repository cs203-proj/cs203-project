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

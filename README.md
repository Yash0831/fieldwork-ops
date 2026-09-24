# fieldwork-ops — Field Service Work-Order & SLA Management Platform

A backend system for tracking field service work orders through a guarded
lifecycle, enforcing SLA policies per priority, detecting breaches
automatically, and keeping a defensible record of response and resolution
times when a contract includes penalty clauses — plus a React operations
console for dispatchers, technicians, and requesters.

**Status: complete portfolio project.** This is an independently built
engineering project to demonstrate backend system design: transactional
workflows, concurrency control, idempotent APIs, scheduled background
processing, and operational hygiene. It is not affiliated with any employer,
**not deployed anywhere, and has never carried production traffic.** All
demo data (the "Meridian Facilities Group" company, people, and logins) is
fictional.

## Features

- **Guarded work-order lifecycle** — `OPEN → ASSIGNED → IN_PROGRESS →
  ON_HOLD → RESOLVED → CLOSED` (+ `CANCELLED`), enforced by an explicit
  state machine; illegal transitions return 422
- **SLA policies per priority** (P1–P4) with response and resolution
  targets, optional category overrides; automatic **breach detection** on a
  scheduled scan with ON_HOLD pause accounting and an immutable breach
  record
- **Workload-aware dispatch** — assign only to active technicians under
  the open-ticket cap (default 8), with optimistic locking so concurrent
  assignments fail loudly (409) instead of overwriting each other
- **Idempotent intake** — optional `Idempotency-Key` header on ticket
  creation: same key + same payload replays the original response; same
  key + different payload is a 409
- **Comments, status history, S3 attachments** (LocalStack in local dev)
- **Notifications with retry** — domain events create notification records
  with dedupe keys; a scheduled worker retries with exponential backoff and
  dead-letters after 5 attempts
- **JWT auth** — short-lived access tokens (15 min) + rotating opaque
  refresh tokens (stored hashed), RBAC across four roles
  (`ADMIN`, `DISPATCHER`, `TECHNICIAN`, `REQUESTER`)
- **Ops dashboard** — ticket counts by status/priority, open breaches, SLA
  compliance gauge, technician load
- **Operational hygiene** — correlation IDs on every request, Flyway
  migrations with JPA `validate`, Testcontainers integration tests

## Tech stack

| Layer        | Choice                                                                          |
|--------------|---------------------------------------------------------------------------------|
| Language     | Java 17                                                                         |
| Framework    | Spring Boot 3.5                                                                 |
| Database     | PostgreSQL 15, schema managed by Flyway (`ddl-auto=validate`)                   |
| Security     | Spring Security, JWT access + rotating refresh tokens, RBAC                      |
| Async        | Spring Scheduler + application events; retry worker with exponential backoff     |
| Storage      | AWS S3 for attachments (LocalStack in local dev)                                |
| Frontend     | React 18 + TypeScript + Vite ops console                                        |
| Build/CI     | Maven, Jenkins                                                                  |
| Containers   | Docker, Docker Compose                                                          |
| Tests        | JUnit 5, Mockito, Testcontainers (PostgreSQL)                                   |

Deliberately absent: no message broker (a scheduled worker over an
outbox-style table covers this system's async needs), no Kubernetes
(Compose is the right scope for a single-instance service), no caching
layer (no read pattern here justifies one yet).

## Architecture

Modular monolith. One deployable service, strict module boundaries:

```
com.fieldwork.ops/
├── auth/          users, teams, roles; admin user management
├── workorder/     lifecycle state machine, idempotent intake, comments
├── sla/           policies, deadline math, breach scans
├── dispatch/      workload-aware assignment, technician directory
├── attachment/    S3 upload/download
├── notification/  event listeners, retry worker, dead-lettering
├── reporting/     dashboard queries, read-only
└── common/        config, security, exception handling, logging
```

Modules communicate through Spring application events (`WorkOrderCreated`,
`StatusChanged`, `SlaBreached`). Controllers stay thin; business rules live
in domain services. Details: `docs/architecture.md`.

## Getting started

Prerequisites: Docker and Docker Compose. (Node 20+ if you want the
frontend dev server too.)

```bash
cp .env.example .env        # then set DB_PASSWORD to something real
docker compose up --build
```

This starts PostgreSQL 15, LocalStack (S3 emulation), and the app. The app
runs Flyway migrations on boot and validates the JPA model against the
migrated schema. LocalStack creates the attachments bucket
(`fieldwork-attachments` by default) via its init hook — no manual step
needed.

- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/metrics
- LocalStack S3: http://localhost:4566

To run the backend outside containers (e.g. from an IDE), start only the
database and LocalStack (`docker compose up db localstack`), keep
`DB_HOST=localhost` in `.env`, and run `FieldworkOpsApplication` — it
picks up the same variables. Note `.env` sets
`S3_ENDPOINT=http://localhost:4566` for that case; inside compose the
app is wired to `http://localstack:4566` instead.

```bash
docker compose down        # stop; data persists in the fieldwork-pgdata volume
```

Full environment-variable reference and a production-readiness checklist:
`docs/deployment.md`.

### Demo logins

Seeded by migration V6 (fictional "Meridian Facilities Group" data, for
local development only). All passwords are `password123`:

| Email | Role | Name |
|---|---|---|
| `priya.nair@meridian.example` | ADMIN | Priya Nair |
| `marcus.webb@meridian.example` | DISPATCHER | Marcus Webb |
| `elena.ruiz@meridian.example` | TECHNICIAN | Elena Ruiz |
| `david.okafor@meridian.example` | TECHNICIAN | David Okafor |
| `sofia.lindqvist@meridian.example` | REQUESTER | Sofia Lindqvist |

Log in via `POST /api/v1/auth/login` with `{"email": …, "password": "password123"}`.

## API overview

Base path `/api/v1`; Bearer token required except `/api/v1/auth/**` and
`/actuator/health`. Full catalogue: `docs/api.md`.

| Area | Highlights |
|---|---|
| Auth | `POST /auth/login`, `POST /auth/refresh` (rotating), `POST /auth/logout` |
| Work orders | `POST /` (idempotent create), `GET /` (filtered, paged queue), `GET /{id}`, `PATCH /{id}/status`, `POST /{id}/assign`, comments, history |
| SLA | `GET/POST /sla/policies`, `PUT /sla/policies/{id}`, `GET /sla/breaches` |
| Dispatch | `GET /technicians` (active technicians with current load) |
| Attachments | `POST /work-orders/{id}/attachments` (multipart), `GET /attachments/{id}/download`, `DELETE /attachments/{id}` |
| Dashboard | `GET /dashboard/summary` |
| Admin | `GET /admin/users`, `PATCH /admin/users/{id}/deactivate|reactivate` |

Errors use a consistent envelope (`timestamp`, `status`, `error`,
`message`, `path`, `fieldErrors`).

## Frontend

React 18 + TypeScript + Vite ops console (`frontend/`): login, ticket
queue with filters, ticket detail (status transitions, assignment, comments,
attachments), and the SLA dashboard. Hand-rolled CSS, no UI framework.

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173, proxies /api/** to the backend
npm test        # unit tests (node --test, no extra dependencies)
npm run build   # typecheck + production build into dist/
```

See `frontend/README.md` for details.

## Testing

```bash
cd backend
mvn test        # unit tests (state machine, services, JWT, dispatch rules)
mvn verify      # everything above + integration tests against
                # Testcontainers-managed PostgreSQL
```

Test layout follows the Maven convention: Surefire runs `*Test` unit
tests (fast, no containers); Failsafe runs `*IntegrationTest` classes
(`WorkOrderLifecycleIntegrationTest`,
`ConcurrentAssignmentIntegrationTest`, `SlaBreachScanIntegrationTest`)
which spin up their own PostgreSQL via Testcontainers — they need a Docker
daemon but never touch a shared database. The frontend has its own
`npm test` suite (JWT handling, error parsing, transition helpers).

## CI

`Jenkinsfile` (declarative pipeline): checkout → backend unit tests →
backend integration tests & package (`mvn verify`) → frontend build & test
→ Docker build → archive artifacts (jar, frontend bundle, test reports).
The integration-test stage needs a Docker daemon on the agent for
Testcontainers. It deliberately does **not** publish to a registry or
deploy anywhere — there is no registry configured and no deployment
target.

## Project structure

```
fieldwork-ops/
├── backend/                 Spring Boot service (Maven)
│   ├── src/main/java/com/fieldwork/ops/   modules (see Architecture)
│   ├── src/main/resources/  application.yml, Flyway migrations (V1–V9)
│   └── Dockerfile           multi-stage build (Maven → JRE)
├── frontend/                React + TypeScript ops console (Vite)
├── docs/                    architecture, api, database, deployment,
│                            troubleshooting
├── localstack/init/         S3 bucket bootstrap for local dev
├── docker-compose.yml       db + localstack + app
├── .env.example             all configuration, documented
└── Jenkinsfile              CI pipeline
```

## Limitations & honesty notes

- **Not deployed, never in production.** Everything here runs locally via
  Compose; treat performance/robustness claims as untested beyond the test
  suite.
- **Single instance.** The breach-scan job and notification retry worker
  need distributed locking (e.g. ShedLock) before running multiple app
  instances.
- **No message broker.** The `notifications` table doubles as the
  outbox/retry queue — the honest seam where a broker would go if volume
  demanded it.
- **Audit log schema without writers.** The `audit_log` table and
  repository exist, but no service writes to them yet; the status-history
  table is the current record of what happened.
- **Logging is plain console + correlation IDs**; there is no JSON log
  layout for aggregation yet.
- **LocalStack S3 is dev-only**; production object storage is documented,
  not provisioned.
- **Demo credentials are fictional** (`password123` on seed accounts) and
  must never be reused anywhere real.
- `docker-compose.yml` does not yet wire `JWT_SECRET` or
  `APP_CORS_ALLOWED_ORIGINS` into the app container — see
  `docs/deployment.md` before any shared deployment.

## License

MIT — see [LICENSE](LICENSE).

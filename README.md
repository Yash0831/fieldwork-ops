# fieldwork-ops — Field Service Work-Order & SLA Management Platform

A backend system for tracking field service work orders through a guarded
lifecycle, enforcing SLA policies per priority, detecting breaches
automatically, and keeping an audit trail that stands up to client contract
disputes.

Built as an independent engineering project to demonstrate backend system
design: transactional workflows, concurrency control, idempotent APIs,
scheduled background processing, and operational hygiene. It is not
affiliated with any employer and has never handled production traffic.

**Project status: Phase 1 — project foundation.** The service boots, runs
migrations, and exposes health endpoints. Domain model, business logic,
APIs, and security arrive in later phases (see Implementation Phases
below).

## Problem

Facilities and IT operations teams take service requests by email and phone.
Requests get lost, nobody knows what is overdue, SLA breaches surface via
angry clients instead of the system, and there is no defensible record of
response times when a contract includes penalty clauses.

This system replaces that with:

- A guarded work-order lifecycle (`OPEN → ASSIGNED → IN_PROGRESS → ON_HOLD → RESOLVED → CLOSED`)
- SLA policies per priority with response and resolution targets
- Automatic breach detection, escalation, and an immutable breach record
- Workload-aware technician dispatch with concurrency-safe assignment
- Comment threads, status history, S3-backed attachments
- Notification records with retry and dead-letter handling
- An ops dashboard for SLA compliance and team load

## Tech stack

| Layer        | Choice                                                                          |
|--------------|---------------------------------------------------------------------------------|
| Language     | Java 17                                                                         |
| Framework    | Spring Boot 3.5                                                                 |
| Database     | PostgreSQL 15, schema managed by Flyway                                         |
| Security     | Spring Security, JWT access + rotating refresh tokens, RBAC (Phase 5)           |
| Async        | Spring Scheduler + application events; retry workers with exponential backoff   |
| Storage      | AWS S3 for attachments (LocalStack in local dev, Phase 7)                       |
| Frontend     | React + TypeScript ops console (Phase 8)                                        |
| Build/CI     | Maven, Jenkins                                                                  |
| Containers   | Docker, Docker Compose                                                          |
| Tests        | JUnit 5, Mockito, Testcontainers                                                |

A note on what is deliberately absent: no message broker (scheduled workers
and an outbox-style table cover this system's async needs honestly), no
Kubernetes (Compose is the right scope for a single-instance service), no
caching layer (no read pattern here justifies one yet).

## Architecture

Modular monolith. One deployable service, strict module boundaries:

```
com.fieldwork.ops/
├── auth/          login, JWT, users and roles (Phase 5)
├── workorder/     lifecycle state machine, idempotent intake (Phases 2–4)
├── sla/           policies, deadline math, breach scans (Phases 2–4, 6)
├── dispatch/      workload-aware assignment (Phases 3–4)
├── attachment/    S3 upload/download (Phase 7)
├── notification/  event listeners, retry worker (Phase 6)
├── reporting/     dashboard queries, read-only (Phase 8)
└── common/        config, security, exception handling, logging
```

Modules communicate through Spring application events (`WorkOrderCreated`,
`StatusChanged`, `SlaBreached`). Controllers stay thin; business rules live
in domain services.

Further reading (arrive with their phases): `docs/architecture.md`,
`docs/database.md`, `docs/api.md`, `docs/deployment.md`,
`docs/troubleshooting.md`.

## Local setup

Prerequisites: Docker and Docker Compose.

```bash
cp .env.example .env        # adjust DB_PASSWORD for shared machines
docker compose up --build
```

This starts PostgreSQL 15 and the app. The app runs Flyway migrations on
boot and validates the JPA model against the migrated schema.

- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/metrics

To run the backend outside containers (e.g. from an IDE), start only the
database (`docker compose up db`), keep `DB_HOST=localhost` in `.env`, and
run `FieldworkOpsApplication` — it picks up the same variables.

To stop and remove containers (data persists in the `fieldwork-pgdata`
volume):

```bash
docker compose down
```

## Configuration

All configuration comes from environment variables; see `.env.example`
for the full list. Nothing secret is committed to the repo.

| Variable      | Default     | Purpose                                  |
|---------------|-------------|------------------------------------------|
| `APP_PORT`    | `8080`      | Published host port for the API          |
| `DB_HOST`     | `localhost` | Database host (use `db` inside compose)  |
| `DB_PORT`     | `5432`      | Database port                            |
| `DB_NAME`     | `fieldwork` | Database name                            |
| `DB_USER`     | `fieldwork` | Database user                            |
| `DB_PASSWORD` | `changeme`  | Database password (change for real use)  |

## Testing

```bash
cd backend
mvn test          # unit tests (arrive in Phase 9)
```

Integration tests run against Testcontainers-managed PostgreSQL in Phase 9;
the Jenkinsfile already reserves the stage.

## Implementation phases

1. **Foundation** — skeleton, module packages, config, Docker Compose, CI skeleton (this phase)
2. **Database/domain model** — Flyway migrations, JPA entities, repositories, seed data
3. **Core business logic** — state machine, SLA engine, dispatch rules, idempotent creation
4. **REST APIs** — controllers, DTOs, validation, pagination, global exception handling
5. **Authentication/security** — JWT, refresh tokens, RBAC, ownership checks
6. **Async/event processing** — domain events, breach-scan job, notification retry worker
7. **Attachments** — S3 integration, compensating deletes on failure
8. **Frontend** — React ops console: queue, ticket detail, dashboard
9. **Testing & observability** — unit + integration + security tests, structured JSON logging
10. **Deployment & docs** — Jenkinsfile completion, full README, architecture docs, troubleshooting

## Known limitations

- Single instance. The breach-scan job and notification worker will need
  distributed locking (e.g. ShedLock) before running multiple app
  instances.
- No message broker: if notification volume ever outgrows a scheduled
  worker, that module is the seam where one gets introduced.
- LocalStack S3 emulation is dev-only; production object storage
  configuration is documented, not provisioned.

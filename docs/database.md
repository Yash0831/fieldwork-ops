# Database

PostgreSQL 15. Schema is managed exclusively by **Flyway**; the app boots
with `hibernate.ddl-auto=validate`, so the JPA entities must match the
migrated schema 1:1 — a mismatch fails startup loudly instead of silently
drifting.

## Migrations

`backend/src/main/resources/db/migration/`:

| Version | Contents |
|---|---|
| V1 | Foundation placeholder: a `schema_version_marker` row so the app booted during early development. Harmless, historical. |
| V2 | Identity domain: `roles`, `teams`, `users`. |
| V3 | Work-order domain: `work_orders`, `work_order_status_history`, `comments`, `attachments`, `idempotency_keys`, plus the `ticket_number_seq` sequence. |
| V4 | SLA domain: `sla_policies`, `sla_breaches`. |
| V5 | Operations domain: `notifications` (retry queue/outbox), `audit_log` (append-only). |
| V6 | Seed data: roles, SLA policies, demo teams and users (fictional "Meridian Facilities Group"; all demo logins share password `password123`). Idempotent via fixed UUIDs + `ON CONFLICT DO NOTHING`. |
| V7 | ON_HOLD pause accounting on `work_orders` (`sla_paused_seconds`, `on_hold_since`); `CANCELLED` added to the status check constraints. |
| V8 | `refresh_tokens` (rotating refresh tokens, SHA-256 hashes only). |
| V9 | `notifications.dedupe_key` with a unique index (notification idempotency). |

Migrations are forward-only and immutable: once applied to any shared
database, a migration is never edited — a new version supersedes it.

## Key constraints and indexes

- **Primary keys**: UUID everywhere, `DEFAULT gen_random_uuid()` (built into
  PostgreSQL 13+, no extension needed).
- **Human-facing identifiers**: `work_orders.ticket_number` is `UNIQUE`
  (`WO-001000`, …), allocated from `ticket_number_seq` by the service layer.
- **Check constraints** keep enums honest at the database level:
  `chk_work_orders_status` (OPEN/ASSIGNED/IN_PROGRESS/ON_HOLD/RESOLVED/CLOSED/CANCELLED),
  `chk_work_orders_priority` (P1–P4), `chk_roles_name`, SLA minute positivity.
- **Breach uniqueness**: `UNIQUE(work_order_id, breach_type)` on
  `sla_breaches` — a ticket can breach each target at most once, so the
  scan job is safe to re-run.
- **Policy uniqueness**: one *active* policy per `(priority, category)` via
  a partial unique index; inactive rows accumulate as policy history.
- **Notification dedupe**: `UNIQUE` on `notifications.dedupe_key`
  (nullable; NULLs are distinct in Postgres, so non-event rows are never
  subject to dedupe).
- **Refresh tokens**: `UNIQUE(token_hash)`; index on `user_id` for
  family-wide revocation on reuse detection.
- **Foreign keys**: `work_orders.requester_id → users` (not null),
  `assignee_id → users` (`ON DELETE SET NULL`), `team_id → teams`
  (`ON DELETE SET NULL`). `created_by`/`updated_by` are free-form actor
  references (username or `system`/`seed`), *not* FKs, so system-generated
  rows and deleted users never break referential integrity.

## Conventions

- All timestamps are `TIMESTAMPTZ`; the app stores UTC and converts at the
  edges (`hibernate.jdbc.time_zone: UTC`).
- Status/priority are `VARCHAR` + `CHECK`, not native enums: readable in
  ad-hoc SQL, zero-cost to extend, mapped as plain JPA enums.

## Optimistic locking

`work_orders` carries a `version` column mapped with JPA `@Version`. Every
`UPDATE` bumps it; two concurrent writers (e.g. two dispatchers assigning
the same ticket) race on the version — the loser's flush throws
`OptimisticLockException`, the transaction rolls back, and the API returns
**409** with "reload and retry". Callers must retry; there is no silent
last-writer-wins. Covered by `ConcurrentAssignmentIntegrationTest`.

## Seed data

V6 seeds the four roles, default SLA policies per priority, three demo
teams, and five fictional users — one per role plus an extra technician —
all with the password `password123` (BCrypt hash, verified at seed time).
See the [README](../README.md#demo-logins) for the login table. This data
exists for local development and demos only; a real deployment would seed
its own users and rotate all credentials.

## Flyway hygiene

- `flyway.validate-on-migrate` is on: a checksum mismatch between the
  migration files and the applied history fails the boot.
- Never edit an applied migration. Never reorder versions. If a migration
  fails halfway, fix forward with a new version — Flyway's `flyway_schema_history`
  table records exactly what applied.

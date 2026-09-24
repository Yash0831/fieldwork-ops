# Architecture

fieldwork-ops is a **modular monolith**: one deployable Spring Boot service
with strict package boundaries. Modules never reach into each other's
internals; they interact through services, Spring application events, and
the shared database schema (owned table-by-table by one module each).

## Package layout

```
com.fieldwork.ops/
├── FieldworkOpsApplication.java
├── auth/            users, teams, roles; admin user management
│                    (AdminUserController, User, Team, Role, RefreshToken)
├── workorder/       the transactional core: lifecycle state machine,
│                    idempotent intake, ticket numbers, comments, history
├── sla/             policies, deadline math, breach scan job, breach records
├── dispatch/        workload-aware technician assignment + directory
├── attachment/     S3 upload/download of ticket attachments
├── notification/    domain-event listeners, notification records,
│                    retry worker with exponential backoff and dead-lettering
├── reporting/       read-only dashboard queries
└── common/
    ├── audit/       AuditLog entity/repository (append-only record)
    ├── config/      AsyncConfig, TimeConfig (Clock bean)
    ├── exception/   domain exceptions mapped to HTTP statuses
    ├── logging/     CorrelationIdFilter, MDC propagation to async threads
    ├── security/    JWT filter, RBAC config, AuthController/AuthService
    └── web/         GlobalExceptionHandler, ErrorResponse envelope
```

Module communication rules:

- **Synchronous calls** stay inside a module or go through the module's
  service façade (e.g. `dispatch` calls `WorkOrderService`, which owns all
  status changes — nothing outside `workorder` mutates a status directly).
- **Cross-module reactions** use Spring application events (see below).
- The database is shared physically, but each table belongs to exactly one
  module; there are no cross-module joins in repositories.

## Request flow

A typical write request (e.g. `POST /api/v1/work-orders`):

1. **CorrelationIdFilter** assigns (or propagates) a correlation ID and puts
   it on the MDC (`correlationId`), so every log line for the request can be
   tied together.
2. **JwtAuthenticationFilter** validates the Bearer token; on success the
   principal (`CurrentUser`: id, username, role) is stored in the security
   context. `/api/v1/auth/**` and `/actuator/health` are public; everything
   else requires authentication.
3. **Controller** (thin): parses the request, enforces the `@PreAuthorize`
   role gate, delegates to a service.
4. **Service** (owns the rules): validates input, checks the state machine,
   workload rules, or idempotency keys, then mutates entities inside a
   `@Transactional` boundary.
5. **Events published** inside the transaction are handled after commit by
   `@TransactionalEventListener`s, so side effects (notifications) never
   fire for rolled-back work.

Reads (`GET /api/v1/dashboard/summary`, queue search) go straight from
controller to service to repository; `reporting` is intentionally read-only
and holds no domain logic.

## Domain events

Three events cross module boundaries, all Spring application events:

| Event | Published by | Listened by |
|---|---|---|
| `WorkOrderCreatedEvent` | `workorder` (after intake commits) | `notification` → `WorkOrderNotificationListener` |
| `WorkOrderStatusChangedEvent` | `workorder` (after a transition commits) | `notification` → `WorkOrderNotificationListener` |
| `SlaBreachedEvent` | `sla` (breach scan job) | `notification` → listeners |

Event-driven work is **best-effort but durable**: listeners write a
`notifications` row first (with a dedupe key of
`<eventType>:<workOrderId>:<recipientId>`), and delivery is retried
independently by the retry worker. A redelivered or concurrently handled
event cannot double-notify because the dedupe key has a unique index.

## Async workers

Two scheduled jobs, both `@Scheduled` and single-instance (see limitations):

- **SlaBreachScanJob** (`sla` module) — cron `${sla.scan.cron}`,
  default every 5 minutes (`0 */5 * * * *`). For each active policy it finds
  work orders whose response or resolution deadline has passed (with ON_HOLD
  pause accounting via `sla_paused_seconds`), inserts an immutable
  `sla_breaches` row (unique per `(work_order_id, breach_type)`, so re-runs
  are safe), and publishes `SlaBreachedEvent`.
- **NotificationRetryWorker** (`notification` module) — fixed delay
  `${notification.retry.fixed-delay}`, default 60 s. Selects up to
  `${notification.retry.batch-size}` (default 100) `PENDING`/`FAILED` rows
  whose `next_retry_at` has passed, attempts delivery, and backs off
  exponentially up to `${notification.retry.max-attempts}` (default 5)
  attempts, after which the row goes to `DEAD_LETTER` for human review.
  `MdcTaskDecorator` propagates the correlation ID onto async threads.

## Concurrency model

- **Optimistic locking** on `work_orders.version` (`@Version`): concurrent
  assign/status-change requests race on the row version; the loser gets
  `OptimisticLockException`, rolled back, and mapped to **409 Conflict**
  ("reload and retry"). There is no silent last-writer-wins.
- **Ticket numbers** come from the `ticket_number_seq` sequence
  (`WO-001000`, …), so concurrent creations never collide.
- **Idempotent intake**: `POST /api/v1/work-orders` accepts an optional
  `Idempotency-Key` header. Same key + same payload within 24 h replays the
  original 201 response verbatim; same key + different payload returns
  **409 Conflict**.

## Honest limitations

- **Single-instance scheduler/retry.** The breach-scan job and notification
  retry worker have no distributed locking; running two app instances would
  double-scan breaches (harmless — inserts are guarded by the unique index)
  and double-deliver notifications (mitigated by the dedupe key, but not
  eliminated). Add ShedLock or an equivalent before scaling horizontally.
- **No message broker.** The `notifications` table doubles as the outbox and
  retry queue. That is the honest seam: if notification volume ever outgrows
  a scheduled worker, introduce a broker there.
- **Audit table without writers.** The `audit_log` table and
  `AuditLogRepository` exist (append-only schema), but no service writes to
  them yet. Do not claim an audit trail exists; status history
  (`work_order_status_history`) is the current record of what happened.
- **Logging is plain console + correlation ID.** The correlation ID is on
  the MDC for every request, but there is no JSON log layout configured —
  log aggregation would need one.
- **LocalStack S3 emulation is dev-only.** Production object storage
  configuration is documented (see `docs/deployment.md`), not provisioned.

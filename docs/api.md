# API reference

Base path: `/api/v1`. All timestamps are ISO-8601 with offset. All responses
use the standard error envelope on failure:

```json
{
  "timestamp": "2026-09-23T21:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Technician already holds 8 open tickets",
  "path": "/api/v1/work-orders/…/assign",
  "fieldErrors": []
}
```

`fieldErrors` carries per-field validation failures (empty — never null —
otherwise). Errors never leak internals: bad credentials and deactivated
accounts both return a generic 401 message.

## Authentication

Public endpoints: `POST /api/v1/auth/**` and `GET /actuator/health`.
Everything else requires `Authorization: Bearer <accessToken>`.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/auth/login` | public | `{email, password}` → `{accessToken, refreshToken, expiresIn, tokenType}`. Access tokens live 15 min (`app.jwt.access-token-ttl`). |
| POST | `/api/v1/auth/refresh` | public | `{refreshToken}` → new token pair. Refresh tokens are opaque, stored hashed, rotate on every use; replaying a revoked token revokes the whole token family. TTL 7 days (`app.jwt.refresh-token-ttl`). |
| POST | `/api/v1/auth/logout` | public | `{refreshToken}` → revokes that token. |

Roles: `ADMIN`, `DISPATCHER`, `TECHNICIAN`, `REQUESTER`. Gates are enforced
twice: `@PreAuthorize` role checks on the controller and ownership checks in
the service layer (e.g. a REQUESTER can only list their own tickets).

## Work orders

Lifecycle: `OPEN → ASSIGNED → IN_PROGRESS → ON_HOLD → RESOLVED → CLOSED`,
plus `CANCELLED` from `OPEN`/`ASSIGNED`. Legal transitions are enumerated in
`WorkOrderStateMachine`; anything else returns **422**. `CLOSED` and
`CANCELLED` are terminal. Reassignment (`ASSIGNED → ASSIGNED` with a
different technician) is handled by the service and recorded in history, not
a state-machine transition.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/work-orders` | `REQUESTER`, `ADMIN` | Creates a ticket → `201 WorkOrderResponse`. Optional `Idempotency-Key` header: same key + same payload within 24 h replays the original 201 verbatim; same key + different payload → **409**. |
| GET | `/api/v1/work-orders` | any authenticated | Paged queue (`WorkOrderListResponse`: `content`, page metadata; default 20/page, newest first). Filters: `status`, `priority`, `teamId`, `assigneeId`, `requesterId`, `mine`. A `REQUESTER` always sees only their own tickets; any role may pass `mine=true` for their own. |
| GET | `/api/v1/work-orders/{id}` | any authenticated | `WorkOrderResponse`: ticket number, title, description, priority, category, status, requester/assignee/team summaries, SLA info (deadlines, breach flags), attachments. |
| PATCH | `/api/v1/work-orders/{id}/status` | `TECHNICIAN`, `ADMIN` | `{toStatus, note?}` → guarded transition; illegal move → **422**. |
| POST | `/api/v1/work-orders/{id}/assign` | `DISPATCHER`, `ADMIN` | `{technicianId}`. The assignee must be an active user with the `TECHNICIAN` role; a technician at the workload cap (default 8 open/in-progress tickets, `dispatch.max-open-tickets-per-technician`) → **422**. Concurrent assigns race on the row version; the loser gets **409** (reload and retry). |
| POST | `/api/v1/work-orders/{id}/comments` | `REQUESTER`, `TECHNICIAN`, `ADMIN` | `{body, internal}`. The author is always the authenticated principal — the payload carries no author id. |
| GET | `/api/v1/work-orders/{id}/comments` | any authenticated | Full thread, oldest first. |
| GET | `/api/v1/work-orders/{id}/history` | any authenticated | Status-change history entries. |

### Create payload (`POST /api/v1/work-orders`)

```json
{
  "title": "HVAC unit not cooling — Building C",
  "description": "…",
  "priority": "P2",
  "category": "HVAC",
  "requesterId": "9b9b9b9b-…",
  "teamId": "2a2a2a2a-…",
  "estimatedHours": 3.5
}
```

`title` (required, ≤255 chars), `description` (≤20000), `priority`
(`P1`–`P4`, defaults to `P3`), `category` (≤64 chars, defaults to
`GENERAL`), `requesterId` (required UUID), `teamId` (optional),
`estimatedHours` (optional, ≤9999.99).

## SLA

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/sla/policies` | any authenticated | Active policies. |
| POST | `/api/v1/sla/policies` | `ADMIN` | `{name, priority, category?, responseMinutes, resolutionMinutes, active?}` → `201 SlaPolicyResponse`. `responseMinutes`/`resolutionMinutes` must be positive, and response ≤ resolution (enforced by `SlaService`, rejects with 400). A policy covers one priority; a `category` narrows it, `null` means "all categories". |
| PUT | `/api/v1/sla/policies/{id}` | `ADMIN` | Full update of a policy. |
| GET | `/api/v1/sla/breaches` | any authenticated | Breach records, newest first. Optional `from`/`to` (ISO-8601 date-times) bound the deadline window. Each breach is immutable (`resolvedAt` is the only field ever updated, when the ticket clears it); a work order can breach each type at most once. |

Breaches are detected by the scheduled scan job (default every 5 min), not
by the API. `ON_HOLD` time pauses the SLA clock (`sla_paused_seconds`).

## Technicians (dispatch)

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/technicians` | `DISPATCHER`, `ADMIN` | Active technicians with current load: `{id, username, fullName, teamName, openTickets}`. Powers the assign picker. |

## Attachments

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/work-orders/{id}/attachments` | `TECHNICIAN`, `DISPATCHER`, `REQUESTER` | `multipart/form-data` with a `file` part → `201 AttachmentResponse` (`{id, fileName, contentType, sizeBytes, uploadedAt, uploader}`). Allowlist: `image/jpeg`, `image/png`, `application/pdf`, `text/plain`; max 10 MiB (`S3_MAX_FILE_SIZE`, `S3_ALLOWED_CONTENT_TYPES`). Violations → **400**. |
| GET | `/api/v1/attachments/{attachmentId}/download` | any authenticated | File bytes. |
| DELETE | `/api/v1/attachments/{attachmentId}` | `TECHNICIAN`, `DISPATCHER`, `REQUESTER` | Removes the record and the S3 object → **204**. |

Stored in S3 (`S3_BUCKET`, default `fieldwork-attachments`; LocalStack in
local dev — see `docs/deployment.md`).

## Dashboard (reporting)

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/dashboard/summary` | any authenticated | Read-only operational snapshot: `totalWorkOrders`, `workOrdersByStatus`, `openWorkOrdersByPriority`, `openWorkOrders`, `unassignedWorkOrders`, `openBreaches`, `slaCompliancePercent` (share of active tickets with no unresolved breach, 0–100, one decimal — a gauge, not a contractual report), `technicianLoad`, `activePolicies`, `generatedAt`. |

## Admin users

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/admin/users` | `ADMIN` | Every user. The password hash is never exposed. |
| PATCH | `/api/v1/admin/users/{id}/deactivate` | `ADMIN` | Login and refresh stop working; all refresh tokens are revoked immediately. |
| PATCH | `/api/v1/admin/users/{id}/reactivate` | `ADMIN` | Restores a deactivated account. |

## Status codes you will actually see

| Code | Meaning here |
|---|---|
| 400 | Validation failure (with `fieldErrors`), unreadable body, bad attachment upload |
| 401 | Missing/invalid access token, bad credentials, expired refresh token |
| 403 | Authenticated but lacking the role |
| 404 | Unknown id |
| 409 | Idempotency key reused with a different payload; optimistic-lock conflict ("reload and retry") |
| 422 | Illegal status transition, workload cap exceeded, assignee not an active technician |

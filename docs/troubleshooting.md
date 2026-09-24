# Troubleshooting

Common issues and how to resolve them. When in doubt, start with the
health endpoint and the container logs:

```bash
curl http://localhost:8080/actuator/health
docker compose logs app | tail -50
```

Every request carries a correlation ID (response header, and the
`correlationId` key on the MDC in logs) — use it to trace one request
across log lines.

## Authentication: "401 Unauthorized" / tokens expiring

- **Access tokens live 15 minutes** (`app.jwt.access-token-ttl`). A 401 on
  a previously working call usually means the access token expired: call
  `POST /api/v1/auth/refresh` with your refresh token to get a new pair.
  The frontend does this automatically (single-shot refresh, then retries
  the original request once).
- **Refresh failing with 401?** Refresh tokens rotate on every use — the
  old one is revoked the moment the new pair is issued. If two tabs (or a
  retry) consume the same refresh token twice, the second use looks like a
  replay attack and the backend revokes the **entire token family** as a
  safety measure. Log in again to get a fresh family.
- **Deactivated accounts** get 401 on both login and refresh, with a
  deliberately generic message (the API never says which credential failed).
  An admin must reactivate the account (`PATCH /api/v1/admin/users/{id}/reactivate`).
- **JWT_SECRET changed?** All outstanding tokens become invalid immediately
  (they're signed with the old secret). Expected after a rotation — clients
  must log in again.

## Work-order assignment: "422 Unprocessable Entity"

`POST /api/v1/work-orders/{id}/assign` returns 422 for three reasons:

1. **Workload cap**: the technician already holds the maximum number of
   open/in-progress tickets (default 8, `dispatch.max-open-tickets-per-technician`).
   Pick another technician or wait for tickets to resolve.
2. **Not an active technician**: the assignee must be an *active* user
   carrying the `TECHNICIAN` role. Dispatching to a deactivated account, a
   dispatcher, or a requester is rejected.
3. **Illegal state for assignment**: the ticket must be in a state where
   assignment makes sense (the state machine still applies).

A 422 also means an illegal status transition on
`PATCH /api/v1/work-orders/{id}/status` — check the legal transitions in
`docs/api.md` (e.g. you cannot go `RESOLVED → IN_PROGRESS`).

## Idempotency: "409 Conflict" on create

`POST /api/v1/work-orders` with an `Idempotency-Key` header:

- Same key + **same payload** (within 24 h) → the original 201 response is
  replayed verbatim. This is normal; the client retried safely.
- Same key + **different payload** → 409. The key was already used for a
  different ticket. Generate a new key per distinct creation attempt —
  typically one UUID per user action.
- Keys expire after 24 h (`IDEMPOTENCY_KEY_TTL` in `WorkOrderService`);
  after that the same key is treated as new.

A 409 on assign/status-change (not create) is the **optimistic-lock
conflict**: someone else modified the ticket concurrently. Reload it and
retry — there is no silent overwrite.

## S3 / LocalStack connectivity

Symptoms: attachment upload/download fails, or the app logs S3 connection
errors on boot.

- **Inside compose vs outside**: inside `docker compose` the app must reach
  LocalStack at `http://localstack:4566` (the service name). When running
  the app from an IDE with compose's `db`/`localstack` services up,
  `S3_ENDPOINT` must be `http://localhost:4566`. Mixing these up is the most
  common failure.
- **Bucket missing**: the bucket is created by
  `localstack/init/ready.d/01-create-bucket.sh` when the LocalStack
  container becomes healthy. If uploads 404, check `docker compose logs
  localstack` — a healthy container means the init hook ran.
- **Upload rejected (400)**: the file violates `S3_MAX_FILE_SIZE`
  (default 10 MiB) or its content type is not in `S3_ALLOWED_CONTENT_TYPES`
  (`image/jpeg`, `image/png`, `application/pdf`, `text/plain`). This is a
  client error, not an outage.
- **Real AWS instead of LocalStack**: leave `S3_ENDPOINT` empty and set
  real `S3_ACCESS_KEY`/`S3_SECRET_KEY`. The `test`/`test` credentials only
  work against LocalStack.

## Flyway: migration or validation failures

- **"Validate failed: Migration checksum mismatch"**: a migration file that
  was already applied has been modified. Applied migrations are immutable —
  restore the original file, and make the change as a *new* migration
  version instead.
- **"Found non-empty schema without schema history table"**: the database
  has tables but no `flyway_schema_history` (e.g. it was created by hand).
  Either point at a fresh database or baseline properly — do not hand-edit
  the history table.
- **"Migration V… failed"**: fix the SQL, then fix *forward* with a new
  version. Never edit and re-run a failed version against a database where
  it partially applied; inspect `flyway_schema_history` to see exactly
  what ran.
- **JPA validation errors at boot** (`ddl-auto=validate`): an entity field
  doesn't match the migrated column (name, type, nullability). Fix the
  entity or add a migration — the mismatch is real and the loud failure is
  intentional.

## Frontend issues

- **API calls failing in `npm run dev`**: the Vite proxy forwards `/api/**`
  to `http://localhost:8080` by default. If the backend runs elsewhere, set
  `VITE_API_PROXY_TARGET` (e.g. `VITE_API_PROXY_TARGET=http://host:8080 npm
  run dev`).
- **Stuck on the login page after a successful login**: the access token is
  held in memory; a page reload drops it and the refresh flow needs a valid
  refresh token. If the family was revoked (see above), log in again.
- **CORS errors against a non-proxied backend**: set `VITE_API_BASE_URL` at
  build time and add the SPA origin to the backend's
  `APP_CORS_ALLOWED_ORIGINS`.

## "Is it down or is it me?"

1. `docker compose ps` — are `db`, `localstack`, and `app` all up?
2. `curl http://localhost:8080/actuator/health` — the app's own verdict.
3. `docker compose logs db localstack` — dependencies healthy?
4. `docker compose logs app | grep -i error` — the actual exception,
   with its correlation ID for the full trace.

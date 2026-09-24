# Deployment

## Local development (the supported path)

Prerequisites: Docker and Docker Compose. Node 20+ if you also want to run
the frontend dev server.

```bash
cp .env.example .env        # then set DB_PASSWORD to something real
docker compose up --build
```

This starts three services:

| Service | Image | Purpose |
|---|---|---|
| `db` | `postgres:15` | The database. Data persists in the `fieldwork-pgdata` volume. Health-gated on `pg_isready`. |
| `localstack` | `localstack/localstack` (S3 only) | S3 emulation for attachments. The bucket (`fieldwork-attachments` by default) is created by the init hook in `localstack/init/ready.d/01-create-bucket.sh` — no manual step. Health-gated on the LocalStack readiness endpoint, so "healthy" means the bucket already exists. |
| `app` | built from `backend/Dockerfile` | The Spring Boot service. Starts only after `db` and `localstack` are healthy. Runs Flyway migrations on boot and validates the JPA model against the migrated schema (`ddl-auto=validate`). |

- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/metrics
- LocalStack S3: http://localhost:4566

Stop and remove containers (data survives in the volume):

```bash
docker compose down
```

To wipe everything including the database: `docker compose down -v`
(this destroys `fieldwork-pgdata`).

### Running the backend outside containers

Start only the database and LocalStack (`docker compose up db localstack`),
keep `DB_HOST=localhost` in `.env`, and run `FieldworkOpsApplication` from
an IDE — it picks up the same variables. Note `.env` sets
`S3_ENDPOINT=http://localhost:4566` for that case; inside compose the app
is wired to `http://localstack:4566` instead.

### Running the frontend

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173, proxies /api/** to the backend
```

The Vite dev server proxies `/api/**` to `http://localhost:8080`, so the
browser makes same-origin requests and no CORS setup is needed. For a
production build, serve `dist/` from the same origin as the API or set
`VITE_API_BASE_URL` at build time and add the SPA origin to
`APP_CORS_ALLOWED_ORIGINS`.

## Environment variables

All configuration comes from environment variables; see `.env.example`
(root) and `backend/.env.example` for the full list. Nothing secret is
committed to the repo — `.env` is gitignored.

| Variable | Default | Purpose |
|---|---|---|
| `APP_PORT` | `8080` | Published host port for the API |
| `DB_HOST` | `localhost` | Database host (use `db` inside compose) |
| `DB_PORT` | `5432` | Database port |
| `DB_NAME` | `fieldwork` | Database name |
| `DB_USER` | `fieldwork` | Database user |
| `DB_PASSWORD` | `changeme` | Database password — change for any shared environment |
| `JWT_SECRET` | dev-only default | HMAC-SHA-256 signing secret, min 32 bytes. **Required in every real environment**; the default logs a warning at startup. Generate with `openssl rand -base64 48`. Note: `docker-compose.yml` does not currently pass this through — set it in the app environment explicitly (see checklist). |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated SPA origins allowed to call the API |
| `S3_ENDPOINT` | _(empty in compose)_ | S3 endpoint override: LocalStack URL in dev; empty → real AWS S3 |
| `S3_REGION` | `us-east-1` | S3 region |
| `S3_BUCKET` | `fieldwork-attachments` | Bucket for ticket attachments |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `test` / `test` | Dev-only LocalStack credentials; real deployments must set real keys |
| `S3_MAX_FILE_SIZE` | `10485760` | Max attachment size in bytes (10 MiB) |
| `S3_ALLOWED_CONTENT_TYPES` | `image/jpeg,image/png,application/pdf,text/plain` | Upload allowlist, comma-separated |
| `SLA_SCAN_CRON` | `0 */5 * * * *` | Breach-scan schedule |
| `NOTIFICATION_RETRY_FIXED_DELAY` | `60000` | Notification retry poll interval (ms) |
| `NOTIFICATION_RETRY_MAX_ATTEMPTS` | `5` | Attempts before a notification goes to `DEAD_LETTER` |
| `NOTIFICATION_RETRY_BATCH_SIZE` | `100` | Rows claimed per retry poll |

## Production-ish checklist

This project has never been deployed; the list below is what "real"
would require, honestly stated:

1. **Secrets**: set `JWT_SECRET` (≥ 32 bytes), a strong `DB_PASSWORD`, and
   real `S3_ACCESS_KEY`/`S3_SECRET_KEY`. Inject them as environment
   variables or a secrets manager — never in files, images, or the repo.
   Note `docker-compose.yml` does not wire `JWT_SECRET` or
   `APP_CORS_ALLOWED_ORIGINS` into the app container today; add those
   mappings before any shared deployment.
2. **Single instance only** until the scheduler and retry worker get
   distributed locking (see `docs/architecture.md` limitations).
3. **Real object storage**: leave `S3_ENDPOINT` empty and point the app at
   a real S3-compatible bucket with proper IAM credentials.
4. **Database**: managed Postgres with backups; Flyway's
   `validate-on-migrate` already guards schema drift at boot.
5. **TLS**: terminate HTTPS in front of the app (reverse proxy or load
   balancer); the container serves plain HTTP on 8080.
6. **Observability**: the `/actuator/health` endpoint is wired into the
   compose healthcheck; add log aggregation (there is no JSON log layout
   yet) and alerting on `DEAD_LETTER` notification rows.
7. **CORS**: set `APP_CORS_ALLOWED_ORIGINS` to the real SPA origin(s) —
   never `*` when credentials are allowed.

## What CI does (and doesn't)

The `Jenkinsfile` builds, tests (unit + Testcontainers integration), builds
the frontend, builds the backend Docker image, and archives artifacts. It
does **not** push to any registry and deploys nowhere — there is no registry
configured and no deployment target. Those stages belong to a future setup
with a real target, not to this repo as it stands.

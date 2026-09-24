# Live demo deployment guide

This guide puts a real, public fieldwork-ops demo online using only free
tiers — no credit card required for the database, API, or frontend:

| Piece    | Service            | Free tier notes                              |
|----------|--------------------|----------------------------------------------|
| Database | [Neon](https://neon.tech) (Postgres) | Generous free tier, no sleep, no card |
| API      | [Render](https://render.com) (Docker) | Free web service; sleeps after 15 min idle (cold start ~60s) |
| Frontend | [Vercel](https://vercel.com) (Vite)   | Free; instant deploys from GitHub |
| Files (optional) | [Cloudflare R2](https://www.cloudflare.com/developer-platform/r2/) | 10 GB free, S3-compatible |

Live demo architecture:

```
Browser ──> Vercel (React SPA) ──> Render (Spring Boot API) ──> Neon (Postgres)
                                          └─(optional)──> Cloudflare R2 (attachments)
```

Everything the demo needs is already in the repo: the backend reads all
configuration from environment variables, the frontend reads its API URL
from `VITE_API_BASE_URL`, and `render.yaml` defines the Render service.
You only create accounts and paste values.

## Step 1 — Database (Neon, ~3 minutes)

1. Sign up at [neon.tech](https://neon.tech) (use "Sign up with GitHub").
2. Create a project: name `fieldwork-ops`, Postgres 16, region closest to you.
3. Open the project dashboard → **Connection Details**. Note these four values:
   - **Host** (looks like `ep-xxx.us-east-2.aws.neon.tech`)
   - **Database** (`neondb` is fine, or create `fieldwork`)
   - **User** (looks like `neondb_owner`)
   - **Password** (click "show" — copy it now; it is shown once)
4. Done here. The backend runs Flyway on boot, so the schema and demo
   seed data are created automatically on first start.

## Step 2 — API (Render, ~5 minutes)

1. Sign up at [render.com](https://render.com) ("Sign up with GitHub").
2. Dashboard → **New +** → **Blueprint** → connect the
   `Yash0831/fieldwork-ops` repository.
3. Render reads `render.yaml` and shows the `fieldwork-ops-api` service.
   Click **Apply**, then fill in the prompted environment variables:

   | Variable | Value |
   |----------|-------|
   | `DB_HOST` | Neon host from Step 1 |
   | `DB_NAME` | Neon database |
   | `DB_USER` | Neon user |
   | `DB_PASSWORD` | Neon password |
   | `JWT_SECRET` | Generate one: `openssl rand -base64 48` |
   | `APP_CORS_ALLOWED_ORIGINS` | Temporary: `http://localhost:5173` (you will update this in Step 4) |

   Leave the `S3_*` values empty for now (attachments optional — Step 5).
4. Deploy. The first build takes ~5–8 minutes (Maven downloads
   dependencies and compiles). Watch the deploy log; a healthy deploy
   ends with Flyway logging `Successfully applied 10 migrations`.
5. Note your API URL: `https://fieldwork-ops-api.onrender.com`
   (Render shows it on the service page).
6. Smoke test: `https://fieldwork-ops-api.onrender.com/actuator/health`
   should return `{"status":"UP"}`.

## Step 3 — Frontend (Vercel, ~3 minutes)

1. Sign up at [vercel.com](https://vercel.com) ("Continue with GitHub").
2. **Add New…** → **Project** → import `Yash0831/fieldwork-ops`.
3. In **Configure Project**:
   - **Root Directory**: `frontend` (click Edit — this is required; the
     repo root is not a Vite project)
   - **Environment Variables**: add
     `VITE_API_BASE_URL` = `https://fieldwork-ops-api.onrender.com/api/v1`
     (your Render URL from Step 2, plus `/api/v1`)
4. **Deploy**. Vercel builds the Vite app (~1–2 minutes).
5. Note your site URL, e.g. `https://fieldwork-ops.vercel.app`.

## Step 4 — Wire CORS (2 minutes)

The API currently only allows `http://localhost:5173`. Point it at the
real frontend:

1. Render dashboard → `fieldwork-ops-api` → **Environment**.
2. Set `APP_CORS_ALLOWED_ORIGINS` to your Vercel URL exactly, no
   trailing slash: `https://fieldwork-ops.vercel.app`
3. **Save Changes** — Render redeploys automatically (~2 minutes).

## Step 5 — Attachments with Cloudflare R2 (optional, ~10 minutes)

Without this step everything works except file uploads, which return a
clear error. To enable uploads:

1. Sign up at [cloudflare.com](https://cloudflare.com), go to **R2**,
   create a bucket named `fieldwork-attachments`.
2. **R2 → Manage R2 API Tokens** → create a token with Object Read &
   Write on that bucket. Note the **Access Key ID**, **Secret Access
   Key**, and the **endpoint URL**
   (`https://<account-id>.r2.cloudflarestorage.com`).
3. Render dashboard → `fieldwork-ops-api` → **Environment**, set:
   - `S3_ENDPOINT` = the R2 endpoint URL
   - `S3_REGION` = `auto`
   - `S3_BUCKET` = `fieldwork-attachments`
   - `S3_ACCESS_KEY` / `S3_SECRET_KEY` = the token values
4. Save — Render redeploys and uploads start working.

## Step 6 — Try the live demo

Open your Vercel URL. On the login page, click **Dispatcher**,
**Technician**, or **Requester** — the demo credentials fill in
automatically (all demo passwords are `password123`):

| Role       | Email                              |
|------------|------------------------------------|
| Dispatcher | `marcus.webb@meridian.example`     |
| Technician | `elena.ruiz@meridian.example`      |
| Requester  | `sofia.lindqvist@meridian.example` |
| Admin      | `priya.nair@meridian.example`      |

Things to try that show off the real engineering:

- **Dispatcher queue** — assign the unassigned P1 HVAC ticket
  (`WO-2026-001001`); it is already past its response SLA, so watch the
  breach scanner flag it within ~5 minutes of API boot.
- **Ticket detail** — move a ticket through transitions; illegal
  transitions are rejected by the state machine.
- **Concurrency** — open the same ticket in two tabs and resolve in
  both; the second gets a clean optimistic-locking conflict, not silent
  data loss.
- **Dashboard** — SLA compliance %, open-by-priority, technician load.

## Operational notes

- **Cold starts**: Render's free tier sleeps after 15 minutes without
  traffic. The first request after sleep takes ~60 seconds while the
  container boots. This is a free-tier limit, not an app problem.
- **Demo data is fake**: every person, company, and ticket is invented
  for demonstration (see the seed migrations). Do not put real data in.
- **Resetting the demo**: delete the Neon database (or the project) and
  recreate it; the next API boot re-runs Flyway and re-seeds everything.
- **Logs**: Render dashboard → service → **Logs**. Every request carries
  a correlation ID (`X-Correlation-ID` response header) for tracing.
- **JWT secret**: if you ever change `JWT_SECRET`, all existing sessions
  are invalidated (users just sign in again).

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Frontend shows "Failed to fetch" on login | CORS origin mismatch | Step 4: `APP_CORS_ALLOWED_ORIGINS` must exactly match the Vercel URL |
| API `/actuator/health` never comes up | DB connection failed | Check `DB_HOST/DB_NAME/DB_USER/DB_PASSWORD`; Neon requires `DB_URL_PARAMS=?sslmode=require` (already in `render.yaml`) |
| Login returns "Invalid credentials" | Seed didn't run | Check deploy log for `Successfully applied 10 migrations`; if Flyway failed, inspect the error and redeploy |
| First load after idle is very slow | Free-tier sleep | Expected; subsequent requests are fast |
| Attachment upload fails | No object storage configured | Expected without Step 5, or check R2 credentials/bucket name |

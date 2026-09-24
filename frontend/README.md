# Fieldwork Ops — Frontend Console (Phase 8)

React 18 + TypeScript + Vite operations console for the fieldwork-ops backend.
Hand-rolled CSS, no UI framework. Dependencies are deliberately minimal:
`react`, `react-dom`, `react-router-dom`.

## Prerequisites

- Node.js 20+
- The backend API running (default `http://localhost:8080`), or any host
  serving `/api/v1/**`.

## Setup & commands

```bash
npm install     # install dependencies
npm run dev     # start the dev server on http://localhost:5173
npm run build   # typecheck (tsc --noEmit) + production build into dist/
npm run preview # serve the production build locally
npm test        # unit tests (node --test, no extra dependencies)
```

### Dev proxy

`npm run dev` proxies `/api/**` to `http://localhost:8080` (the Spring Boot
backend), so the browser makes same-origin requests and no CORS configuration
is needed. Override the target with the `VITE_API_PROXY_TARGET` env var:

```bash
VITE_API_PROXY_TARGET=http://staging.example.com:8080 npm run dev
```

### Environment

| Variable               | Default              | Purpose                                              |
|------------------------|----------------------|------------------------------------------------------|
| `VITE_API_BASE_URL`    | `/api/v1`            | Base URL for API calls, baked in at **build** time.  |
| `VITE_API_PROXY_TARGET`| `http://localhost:8080` | Dev-server proxy target (dev only, not baked in). |

For production, either serve `dist/` from the same origin as the API (keep
the default) or build with the API origin, e.g.:

```bash
VITE_API_BASE_URL=https://api.example.com/api/v1 npm run build
```

## Screens

| Route          | Screen           | Notes                                                                 |
|----------------|------------------|-----------------------------------------------------------------------|
| `/login`       | Sign in          | Email + password → `POST /api/v1/auth/login`. Backend error envelope messages are shown verbatim. |
| `/`            | Ticket queue     | Status/priority filters, `mine` toggle, server-side pagination over `GET /api/v1/work-orders`. REQUESTERs see only their own tickets (enforced by the backend). SLA-breached rows are highlighted. |
| `/tickets/:id` | Ticket detail    | Detail + SLA panel, status transitions (valid next states only, from a client-side mirror of the backend state machine), dispatcher assignment, comments, history timeline, attachment upload/download. |
| `/dashboard`   | Ops dashboard    | ADMIN + DISPATCHER only. Summary cards, open tickets by status, recent SLA breaches. |

## Auth design

- Tokens are stored **in memory only** (module scope in `src/api/client.ts`);
  they are never written to `localStorage`/`sessionStorage`. Web storage is
  readable by any script on the page, so a single XSS flaw would become
  persistent credential theft. In-memory tokens die with the tab, and the
  backend rotates refresh tokens on each use. Trade-off: a full page reload
  drops the session.
- `src/api/client.ts` attaches the bearer token, and on a 401 attempts
  **exactly one** refresh via `POST /api/v1/auth/refresh` before retrying the
  original request. Concurrent 401s share one in-flight refresh (the backend
  revokes the token family on replay). If refresh fails, the session is
  cleared and the user is sent to `/login`.
- Route guards: `RequireAuth` (signed-in) and `RequireRole` (role from the
  decoded JWT payload — signature is not verified client-side; the backend
  re-authorizes every request, so these gates are UI convenience only).
- Error handling: non-2xx responses are parsed into `ApiError` carrying the
  backend error envelope's `message` plus `fieldErrors[]` (`src/api/errors.ts`).

## TypeScript types

`src/api/types.ts` mirrors the backend DTOs (`WorkOrderResponse` with nested
`SlaInfo`/`UserSummary`/`TeamSummary`, `WorkOrderListResponse` page wrapper,
`DashboardSummaryResponse`, the error envelope, token pair). The JWT role is
decoded from the `role` claim; `sub` is the user id.

## Backend endpoints used

- `GET /api/v1/work-orders/{id}/comments` — full comment thread (oldest first).
- `GET /api/v1/dashboard/summary` — status/priority counts, SLA compliance %,
  technician load, open breaches.
- `GET /api/v1/technicians` — dispatcher-visible technician directory (active
  technicians with current load), powering the assign picker.

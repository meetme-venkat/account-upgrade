# Account Upgrade Frontend

An Angular 22 single-page app for [`account-upgrade-backend`](../account-upgrade-backend). It builds, tests and deploys on its own, and talks to the backend only through the backend's REST API.

## Features

| Page | Backend API | What it does |
|---|---|---|
| **Submit → Real-time** | `POST /api/realtime-upgrade` | Form for one request, with an optional `Idempotency-Key`. Shows the ingestion receipt |
| **Submit → Batch** | `POST /api/batch-upgrade` | Row editor or raw JSON editor, with a sample batch. Shows the accepted and rejected counts and a receipt for each item |
| **Processed** | `GET /api/processed-upgrades` | Outcomes with filters for status and `userId`, auto-refresh, summary stats and paging. Each receipt links here, filtered to its user |
| **Notifications** | `GET /api/notifications` | The backend's mock email outbox, filterable by recipient role or text |
| Header badge | `GET /actuator/health` | Shows whether the backend is up |

The form validates only the checks the backend enforces at ingestion: `userId` is required and `parentEmail` must be well formed. It leaves the eligibility rules (name, age 18–23, balance ≥ $30) to the backend, so you can submit ineligible requests and watch them be declined. Backend validation errors (RFC 9457 problem details) are shown field by field.

## Structure

```
src/app/
  core/api/        models.ts (wire types), upgrade-api.service.ts (HTTP client), api-error.ts
  core/config/     runtime-config.ts: loads /config.json before bootstrap
  shared/          request form model, polling helper, error panel, receipt table, idempotency-key field
  features/submit/        real-time form, batch editor, tab page
  features/processed/     processed-upgrades page
  features/notifications/ notifications page
```

It uses standalone components, signals and zoneless change detection, and each page is lazy-loaded.

## Develop

You need Node 20.19+ (Node 24 LTS recommended).

```bash
npm install
npm start          # http://localhost:4200, with /api and /actuator proxied to http://localhost:8080 (proxy.conf.json)
npm test           # unit tests (Vitest)
npm run build      # production build in dist/account-upgrade-frontend/browser
```

Start the backend first (`cd ../account-upgrade-backend && ./mvnw spring-boot:run`).

> **Windows with Smart App Control / WDAC:** if `ng build` fails with *"An Application Control policy has blocked this file … parser.win32-x64-msvc.node"*, the OS is blocking oxc-parser's native addon. Use its WASM build instead:
> ```bash
> npm i --no-save --force @oxc-parser/binding-wasm32-wasi@$(node -p "require('oxc-parser/package.json').version")
> rm -rf node_modules/@oxc-parser/binding-wasm32-wasi/node_modules/@emnapi
> ```

## Deploy

The same build runs against any backend. The backend location is read at startup from `config.json`, not compiled in.

```bash
docker build -t account-upgrade-frontend .
docker run -p 4200:8080 -e BACKEND_URL=http://host.docker.internal:8080 account-upgrade-frontend
```

The image is unprivileged nginx (non-root, port 8080) serving the static bundle, with production guardrails built in: security headers, a strict CSP, rate limiting, timeouts and startup config validation. See [PRODUCTION_GUARDRAILS.md](PRODUCTION_GUARDRAILS.md). It also proxies `/api/` and `/actuator/health` to `BACKEND_URL`, so the browser sees a single origin and no CORS is needed.

| Environment variable | Default | Purpose |
|---|---|---|
| `BACKEND_URL` | `http://account-upgrade-backend:8080` | Upstream for the reverse proxy (`scheme://host:port`). It is resolved per request, so the frontend starts even if the backend is down |
| `API_BASE_URL` | *(empty)* | Set this only to have the browser call the backend directly, for example when the bundle is hosted on a CDN. The backend must then allow the origin via `UPGRADE_WEB_CORS_ALLOWED_ORIGINS` |
| `POLL_INTERVAL_MS` | `3000` | Auto-refresh interval of the list pages (minimum 1000) |
| `API_RATE_LIMIT_RPS` / `API_RATE_LIMIT_BURST` | `20` / `40` | Per-client rate limit on the `/api` proxy |
| `HSTS_VALUE` | *(empty, header not sent)* | `Strict-Transport-Security` value. Set it only when the site is served over HTTPS |
| `NGINX_RESOLVER` | from `/etc/resolv.conf` | DNS server nginx uses to resolve `BACKEND_URL` |

Run every build gate (formatting, tests with the coverage gate, budgeted build, dependency audit) with `npm run verify`.

`GET /healthz` is the container's own liveness endpoint.

**Static hosting without nginx:** upload `dist/account-upgrade-frontend/browser`, edit its `config.json` to `{"apiBaseUrl": "https://api.example.com"}`, and have the host fall back to `index.html` for unknown paths.

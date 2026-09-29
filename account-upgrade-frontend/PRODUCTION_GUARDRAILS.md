# Production guardrails: account-upgrade-frontend

These are the protections this service enforces before and while it runs in production. Each one lives in code, configuration or CI, not only in this document. The **Enforced by** column says where.

## 1. Fail-fast container startup

`docker/10-guardrails.envsh` runs before nginx starts. On invalid configuration, the container **exits** with an `ERROR:` line instead of serving a broken or unprotected site.

| Setting | Rule |
|---|---|
| `BACKEND_URL` | Must be `http(s)://…`. No quotes, spaces, `;` or `\` (it is inserted into nginx config) |
| `API_BASE_URL` | Empty, or an absolute `http(s)` URL with the same character rules (it goes into `config.json` and the CSP header). Plain `http` to anything other than localhost logs a warning |
| `API_RATE_LIMIT_RPS`, `API_RATE_LIMIT_BURST` | Positive integers |
| `POLL_INTERVAL_MS` | Integer ≥ 1000, so no deployment can make every browser hammer the backend |

## 2. Browser security headers

Set once at the nginx server level, so they are on **every** response: the app, errors and the proxied API. `Cache-Control` is computed with a `map`, so no `location` block uses `add_header`. In nginx, a location that uses `add_header` silently drops all inherited headers.

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' [API_BASE_URL]; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | camera, microphone, geolocation, payment and USB disabled |
| `Cross-Origin-Opener-Policy` | `same-origin` |
| `Strict-Transport-Security` | Sent only when `HSTS_VALUE` is set (for example `max-age=31536000; includeSubDomains`). Set it only when the site is served over HTTPS |
| `Cache-Control` | `no-store` for `config.json`, `immutable` for hashed assets, `no-cache` for `index.html` and routes |

**Keeping the CSP working:** the strict `script-src 'self'` works only because the build emits no inline script. Two build settings are pinned for this, and a CI step checks the result:
- `optimization.styles.inlineCritical: false`. Critical-CSS inlining adds an inline `onload` handler that the CSP would block, leaving the page unstyled.
- No `subresourceIntegrity`. It adds an inline `<script type="importmap">` that the CSP would block, breaking lazy-loaded pages.

`style-src 'unsafe-inline'` is needed because Angular inserts component styles at runtime.

## 3. Reverse-proxy protections

| Guardrail | Behaviour | Config |
|---|---|---|
| Per-client rate limit on `/api/` | `429` beyond the rate and burst (defence in depth: the backend limits too) | `API_RATE_LIMIT_RPS` (20), `API_RATE_LIMIT_BURST` (40) |
| Method allow-list | `/api/` accepts `GET` and `POST` only; `/actuator/health` accepts `GET` only | – |
| Only two backend paths exposed | `/api/` and `/actuator/health`. Every other actuator endpoint is unreachable through the frontend | – |
| Timeouts | Backend: connect 5 s, read and send 30 s. Clients: header and body 10 s, keep-alive 30 s | – |
| Body cap | 5 MB, the same as the backend | – |
| Request correlation | Each request gets an nginx `$request_id`. It is written to the access log (`rid=`) and forwarded as `X-Request-Id`, so the backend logs the same id | – |
| Startup independent of the backend | `BACKEND_URL` is resolved per request, so the UI still loads while the backend is down | `NGINX_RESOLVER` |
| No information leaks | `server_tokens off`. Dotfiles are denied. A missing hashed asset returns `404`, not `index.html` | – |

## 4. Container hardening

| Guardrail | Enforced by |
|---|---|
| nginx runs as a non-root user (uid 101) on port 8080. Only `config.json` is writable by that user | `nginxinc/nginx-unprivileged` base image, `Dockerfile` |
| An image can't be produced unless the unit tests (with the coverage gate) and the budgeted production build pass | `Dockerfile` build stage (`npm run test:ci`) |
| `npm ci --ignore-scripts`: no dependency install scripts run during the image build | `Dockerfile` |
| In compose: `no-new-privileges`, all capabilities dropped, memory and PID limits | `../docker-compose.yml` |

## 5. Application-level guardrails

| Guardrail | Enforced by |
|---|---|
| A backend call that gets no response within 15 s fails with a readable "Request timed out" error, instead of a spinner that never stops | `core/http/timeout.interceptor.ts` |
| Polling never overlaps (a slow response is not stacked with the next tick), and it stops when the page is left | `shared/polled-query.ts` (`exhaustMap`, `takeUntilDestroyed`) |
| The last good data stays visible while the backend is unreachable, with the error shown above it | `polledQuery` |
| Structural input checks before sending (required `userId`, well-formed email, whole-number age). Eligibility is still decided by the backend | `shared/upgrade-request-form.ts` |
| Large result sets are paged 50 rows at a time | `ProcessedUpgradesPage` |
| The backend URL is runtime config, not compiled in, so the same build is promoted through every environment | `core/config/runtime-config.ts` |

## 6. Build and delivery gates

| Gate | Fails when | Enforced by |
|---|---|---|
| Formatting | A source file isn't Prettier-formatted | `npm run format:check` |
| Type safety | Any TypeScript or template type error, or any Angular extended diagnostic | `strict`, `strictTemplates`, `extendedDiagnostics: error` in `tsconfig.json` |
| Tests and coverage | A test fails, or coverage drops below 85% statements, branches and lines, or 75% functions (currently about 90%, 90%, 90% and 79%) | `coverageThresholds` in `angular.json`, `npm run test:ci` |
| Bundle size | Initial bundle > 500 kB (warns at 350 kB), or any component stylesheet > 4 kB | `budgets` in `angular.json` |
| Dependency vulnerabilities | Any high or critical advisory in runtime dependencies | `npm run audit:prod` |
| Toolchain | Node outside `>=20.19 <25` | `engines` plus `engine-strict` in `.npmrc` |
| Image behaviour | Bad config doesn't fail startup, the security headers are missing, HSTS is sent by default, or nginx runs as root | `.github/workflows/frontend.yml` `image` job |
| Dependency freshness | – (weekly update PRs for npm and the base image) | `.github/dependabot.yml` |

Run all the gates locally with:

```bash
npm run verify     # format check, tests + coverage, production build, audit
```

## Pre-deployment checklist

- [ ] `BACKEND_URL` points at the backend service, not a public URL, when both run in one network.
- [ ] `API_BASE_URL` is empty (the proxy is used). If it is set, it is an HTTPS origin and the backend's `UPGRADE_WEB_CORS_ALLOWED_ORIGINS` lists this site's origin.
- [ ] `HSTS_VALUE` is set when the site is served over HTTPS.
- [ ] The container port is `8080`: map it, and point probes at `/healthz`.
- [ ] If another proxy sits in front, its own rate limit and timeouts are at least as strict.

## Known limits

- The nginx rate limit is per container. Replicas each allow the full rate.
- No authentication in the UI. Protect the site at the ingress (SSO or OAuth proxy) before exposing it beyond a trusted network.

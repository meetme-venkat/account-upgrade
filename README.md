# Account Upgrade Platform

Two independently deployable services:

| Folder | Service | Stack | Port |
|---|---|---|---|
| [`account-upgrade-backend/`](account-upgrade-backend) | Event-driven upgrade service: ingestion, eligibility, notifications, persistence, query API | Java 17+, Spring Boot 4, Kafka, PostgreSQL | 8080 |
| [`account-upgrade-frontend/`](account-upgrade-frontend) | Web UI to submit requests and browse outcomes and notifications | Angular 22, served by nginx | 4200 |

The two share no code and no build. The frontend depends only on the backend's REST API, and each folder has its own Dockerfile, tests and README.

```
Browser ──► frontend (nginx) ──/api──► backend ──► Kafka "upgrade-requests" ──► eligibility consumer ──► PostgreSQL
                                                                                    (decision + email outbox, one transaction)
```

## Run with Docker (everything)

```bash
docker compose up --build         # UI http://localhost:4200, API http://localhost:8080
```

This starts PostgreSQL, a 3-broker Kafka cluster, the backend (prod profile) and the frontend.

## Run from source

Needs a Docker engine (Docker Desktop, or Rancher Desktop with `dockerd (moby)`). PostgreSQL and Kafka always run as containers.

```bash
# terminal 1: backend. Starts its PostgreSQL + Kafka containers automatically, then the app
cd account-upgrade-backend
./mvnw spring-boot:run            # Windows PowerShell: .\mvnw.cmd spring-boot:run

# terminal 2: frontend
cd account-upgrade-frontend
npm install
npm start                         # http://localhost:4200 (proxies /api to :8080)
```

## Tests

```bash
cd account-upgrade-backend && ./mvnw test      # PostgreSQL and Kafka start as containers (needs Docker)
cd account-upgrade-frontend && npm test
```

## Production guardrails

Each service documents and enforces its own: [backend](account-upgrade-backend/PRODUCTION_GUARDRAILS.md), [frontend](account-upgrade-frontend/PRODUCTION_GUARDRAILS.md). CI in `.github/workflows/` blocks merges that break them, and `.github/dependabot.yml` keeps dependencies current.

## Continuous delivery

Every push to `main` is tested, built, scanned and deployed to Rancher Desktop by a local script pipeline (a scheduled task on the deployment machine), with a smoke test and automatic rollback. GitHub checks pull requests and publishes the images to GHCR, but the deployment doesn't depend on it. Setup and operation: [deploy/README.md](deploy/README.md).

See [the backend README](account-upgrade-backend/README.md) for API details and sample payloads, and [the frontend README](account-upgrade-frontend/README.md) for deployment settings.

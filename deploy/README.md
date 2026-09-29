# Continuous delivery to Rancher Desktop

Every push to `main` is tested, built, scanned and deployed to Rancher Desktop by scripts on the deployment machine.
GitHub only hosts the code: there is no runner to register, no registry to pull from, and no credentials to store.

| Script | Role |
|---|---|
| [`pipeline.ps1`](pipeline.ps1) | The pipeline: checks `main` for a new commit, then tests, builds, scans and deploys it |
| [`deploy.ps1`](deploy.ps1) | The deployment step: rolls the stack forward, smoke tests it, rolls back on failure |
| [`install-pipeline.ps1`](install-pipeline.ps1) | Schedules the pipeline every few minutes as a Windows scheduled task |

```
every 2 minutes (scheduled task, as you, while you're logged on)
  git fetch main ─► new commit?  no ─► done
        │ yes
        ▼
  clean checkout of that commit      %LOCALAPPDATA%\account-upgrade\pipeline\repo
  backend tests                      mvnw verify: unit + integration tests (Testcontainers), coverage gate, enforcer
  build images                       every service with a build context in the commit's docker-compose.yml:
                                     local/account-update-db-schema, account-upgrade-{backend,frontend}:<sha>
                                     (the frontend's tests and budgeted production build run inside its image build)
  Trivy scan                         no fixable CRITICAL vulnerability
  deploy.ps1                         compose up --wait, in order: schema job (must exit 0) ─► backend (healthy)
                                     ─► frontend (healthy) ─► smoke test
                                       └─ failure: roll back to the images that were running
```

| Guarantee | How |
|---|---|
| Nothing untested is deployed | Each stage must pass before the next starts |
| Builds are reproducible | Clean checkout (`git clean -ffdx`) of exactly the commit on `main`, separate from any working copy |
| Releases are traceable | Images are tagged with the full commit SHA and labelled with it (`org.opencontainers.image.revision`) |
| The stack definition matches the images | The commit's own `docker-compose.yml` and `deploy.ps1` are used, and the images to build are read from that `docker-compose.yml` |
| Schema before code | The schema job runs first on every deployment; the backend starts only if it exits 0, the frontend only once the backend is healthy. A failed migration replaces nothing and triggers the rollback |
| One run at a time | A lock file, and the scheduled task never overlaps itself |
| A bad release doesn't stay live | Health checks + smoke test (API → Kafka → PostgreSQL → email outbox, and the UI's `/api` proxy), automatic rollback |
| A broken commit isn't retried every 2 minutes | It is attempted once; the next push to `main`, or `-Force`, runs again |
| Auditable | One log per run, a deployment history, and the current state (see below) |

## Setup

Requirements on the deployment machine: Git, a JDK 17 or later on `PATH` (for the backend tests), and Rancher
Desktop with the **dockerd (moby)** engine (*Preferences → Container Engine*).

```powershell
# from a clone of the repository
powershell -NoProfile -ExecutionPolicy Bypass -File deploy\install-pipeline.ps1
```

This registers the scheduled task `AccountUpgrade-CD`. It runs as you, only while you are logged on (the same
session as Rancher Desktop), needs no password or admin rights, and shows no window. The first run deploys the
current `main`.

The pipeline owns one compose stack, the project `account-upgrade`. Stop any stack started by hand from a clone first,
because it holds ports 8080 and 4200 (the deployment refuses to start otherwise and names the containers):

```powershell
docker compose -p <project> down      # e.g. -p mercur for a clone in D:\MERCUR. Add -v to also delete its data
```

## Operating it

The installed copy of the pipeline is `%LOCALAPPDATA%\account-upgrade\pipeline\bin\pipeline.ps1`; `deploy\pipeline.ps1`
in a clone works the same.

| Task | How |
|---|---|
| Deploy | Push or merge to `main`; it's live within a few minutes |
| What is deployed, last result | `pipeline.ps1 -Status` |
| Deploy now, without waiting | `pipeline.ps1` |
| Roll back / redeploy a release | `pipeline.ps1 -Commit <sha>` (rebuilds and tests that commit; must include `deploy\deploy.ps1`) |
| Retry a failed commit | `pipeline.ps1 -Force` |
| Faster manual run | `-SkipTests` and/or `-SkipScan` |
| Pause / resume | `Disable-ScheduledTask AccountUpgrade-CD` / `Enable-ScheduledTask AccountUpgrade-CD` |
| Change the interval | `install-pipeline.ps1 -IntervalMinutes 5` |
| Update after changing `pipeline.ps1` | Run `install-pipeline.ps1` again (adding or removing a service does not need it: the images to build come from `docker-compose.yml`) |
| Remove | `install-pipeline.ps1 -Uninstall` |
| See what's running | `docker compose -p account-upgrade ps` |

Run the scripts with `powershell -NoProfile -ExecutionPolicy Bypass -File <script> [options]`.

Files, all under `%LOCALAPPDATA%\account-upgrade\`:

| File | Content |
|---|---|
| `deployments.log` | One line per deployment: `DEPLOYED`, `ROLLED BACK` or `FAILED`, with the commit |
| `pipeline\logs\<time>-<sha>.log` | Full output of each run (last 50 kept) |
| `pipeline\state.json` | Deployed commit, last commit tried and its result |
| `pipeline\poll.log` | Checks that could not run (network down, Rancher Desktop not started) |

Endpoints: UI http://127.0.0.1:4200, API http://127.0.0.1:8080. Each deployment leaves one `deploy-smoke-<timestamp>`
record in the processed requests, from its smoke test.

## GitHub's part

- Pull requests: the `backend` and `frontend` workflows check every change.
- `main`: [`publish.yml`](../.github/workflows/publish.yml) runs both again, then builds, scans and publishes the images
  to GHCR (`ghcr.io/<owner>/<service>:<sha>`, with SBOM and provenance) as release artifacts for other environments.
  The Rancher Desktop deployment doesn't depend on it.

## Notes and limits

- **Deployment delay.** A push is picked up at the next check (default every 2 minutes). A full run takes several
  minutes, mostly the backend's integration tests.
- **Only while you're logged on.** Rancher Desktop runs in your login session, so the pipeline does too. Commits
  pushed meanwhile are deployed (the latest one) at the next check after you log on.
- **Database migrations go forward only.** The `account-update-db-schema` job (Liquibase) migrates the schema before
  the backend starts. A rollback restores the previous images but not the previous schema, so keep migrations
  backward compatible (expand, then contract) for rollback to be safe. See
  [account-update-db-schema](../account-update-db-schema/README.md#changing-the-schema).
- **Short downtime on each deployment.** One backend and one frontend container are replaced in place. Zero-downtime
  rollouts need several replicas behind a load balancer, which means Kubernetes (Rancher Desktop includes k3s).
- **Image retention.** The machine keeps the current, previous and 5 older releases of each image for fast rollback.

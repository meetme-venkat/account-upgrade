# Continuous delivery to Kubernetes (Rancher Desktop)

Every push to `main` is tested, built, scanned and deployed to Kubernetes, the k3s cluster built into Rancher
Desktop, by scripts on the deployment machine. GitHub only hosts the code: there is no runner to register, no
registry to pull from, and no credentials to store.

| File | Role |
|---|---|
| [`pipeline.ps1`](pipeline.ps1) | The pipeline: checks `main` for a new commit, then tests, builds, scans and deploys it |
| [`deploy.ps1`](deploy.ps1) | The deployment step: rolls the release out to Kubernetes in order, smoke tests it, rolls back on failure |
| [`k8s/base`](k8s/base) | The Kubernetes manifests (kustomize): namespace, PostgreSQL, Kafka, schema Job, backend, frontend |
| [`install-pipeline.ps1`](install-pipeline.ps1) | Schedules the pipeline every few minutes as a Windows scheduled task |
| [`account-upgrade-e2e`](../account-upgrade-e2e) | End-to-end tests (JUnit) of the running deployment |

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
  deploy.ps1                         kubectl apply, one component at a time, namespace account-upgrade:
                                       postgres, kafka (ready) ─► schema Job (must succeed) ─► backend (rolled out)
                                       ─► frontend (rolled out) ─► smoke test
                                       └─ failure: roll back to the images that were running
```

## What runs in the cluster

Namespace `account-upgrade` (Pod Security: `baseline` enforced):

| Workload | Kind | Replicas | Notes |
|---|---|---|---|
| `postgres` | StatefulSet | 1 | `postgres:16`, 2 Gi volume. Cluster-internal only |
| `kafka` | StatefulSet | 3 | KRaft brokers+controllers `kafka-{0,1,2}.kafka:9092`, 2 Gi volume each; topics get replication factor 3, `min.insync.replicas` 2 |
| `account-update-db-schema` | Job | 1 run | Liquibase; recreated on every deployment (a no-op when the schema is current) |
| `account-upgrade-backend` | Deployment | 2 | Service `LoadBalancer` on port **8080**; startup/liveness/readiness probes on the actuator health groups |
| `account-upgrade-frontend` | Deployment | 2 | Service `LoadBalancer` on port **4200**; proxies `/api` to the backend Service |

Rancher Desktop publishes `LoadBalancer` services on the host, so the endpoints are the same as before: UI
http://127.0.0.1:4200, API http://127.0.0.1:8080.

The images are the ones the pipeline builds into Rancher Desktop's Docker engine (`local/<service>:<sha>`), which
its Kubernetes uses directly: nothing is pushed anywhere. `deploy.ps1` pins them in a kustomize overlay it
generates at `k8s/release` (git-ignored); the base manifests only hold placeholders.

The secret `account-upgrade-secrets` (PostgreSQL password, JWT signing key) is not in the repository: `deploy.ps1`
creates it with random values on the first deployment and keeps it afterwards (PostgreSQL only reads the password
when it initialises its volume, and a new signing key would sign everyone out). To read it:

```powershell
kubectl -n account-upgrade get secret account-upgrade-secrets -o jsonpath='{.data.postgres-password}' |
    % { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) }
```

| Guarantee | How |
|---|---|
| Nothing untested is deployed | Each stage must pass before the next starts |
| Builds are reproducible | Clean checkout (`git clean -ffdx`) of exactly the commit on `main`, separate from any working copy |
| Releases are traceable | Images are tagged with the full commit SHA and labelled with it (`org.opencontainers.image.revision`) |
| The cluster definition matches the images | The commit's own `deploy/k8s` manifests and `deploy.ps1` are used |
| Schema before code | The schema Job runs first on every deployment; the backend is rolled out only if it succeeds, the frontend only once the backend's rollout completed. A failed migration replaces nothing and triggers the rollback |
| No downtime | 2 replicas each for backend and frontend, rolling updates with `maxUnavailable: 0` (a new pod must pass its readiness probe before an old one stops), a `preStop` pause so the Service stops routing before shutdown, graceful shutdown, PodDisruptionBudgets |
| One run at a time | A lock file, and the scheduled task never overlaps itself |
| A bad release doesn't stay live | Rollout deadlines (`progressDeadlineSeconds`), probes, smoke test (the API refuses a request without a token; login as the administrator (`-AdminUsername` / `-AdminPassword`, or `UPGRADE_SECURITY_ADMIN_*`); API → Kafka → PostgreSQL → email outbox with the token; the UI's `/api` proxy), automatic rollback |
| The right cluster | Every `kubectl` call names its context (`-Context`, default `rancher-desktop`, or `ACCOUNT_UPGRADE_KUBE_CONTEXT`) |
| Valid manifests | The `k8s-manifests` workflow renders them and validates every resource against the Kubernetes schemas on each pull request |
| A broken commit isn't retried every 2 minutes | It is attempted once; the next push to `main`, or `-Force`, runs again |
| Auditable | One log per run, a deployment history, and the current state (see below) |

## Setup

Requirements on the deployment machine: Git, a JDK 17 or later on `PATH` (for the backend tests), and Rancher
Desktop with:

- **Container Engine: dockerd (moby)** (*Preferences → Container Engine*), so the cluster sees the images the
  pipeline builds;
- **Kubernetes enabled** (*Preferences → Kubernetes*), which creates the `kubectl` context `rancher-desktop`;
- at least **6 GB of memory and 4 CPUs** available to it: the deployment requests about 2.5 GB (3 Kafka brokers,
  2 backends, PostgreSQL, 2 frontends), and k3s needs some too. On Windows it runs in WSL, which gets half the
  host's memory by default (limit it in `%USERPROFILE%\.wslconfig`, not in Rancher Desktop); on macOS and Linux set
  it in *Preferences → Virtual Machine*.

The command line equivalent of the first two settings, which also starts Rancher Desktop:

```powershell
rdctl start --kubernetes.enabled=true --container-engine.name=moby
```

If `docker` then fails with `timed out dialing Hyper-V socket` while Kubernetes works, restart Rancher Desktop
(`rdctl shutdown`, then the command above).

```powershell
# from a clone of the repository
powershell -NoProfile -ExecutionPolicy Bypass -File deploy\install-pipeline.ps1
```

This registers the scheduled task `AccountUpgrade-CD`. It runs as you, only while you are logged on (the same
session as Rancher Desktop), needs no password or admin rights, and shows no window. The first run deploys the
current `main`. If the task was installed before the move to Kubernetes, run the installer again.

### Moving from the docker compose deployment

Earlier releases ran as the compose project `account-upgrade`. It publishes the same ports, so `deploy.ps1` refuses
to start while a compose stack holds 8080 or 4200 and names its containers. Stop it once:

```powershell
docker compose -p account-upgrade down      # keeps its volume account-upgrade_postgres-data; -v would delete it
```

The Kubernetes deployment starts with an empty database. To carry the old data over, dump it from the compose
volume and restore it into the new PostgreSQL before or after the first deployment:

```powershell
docker compose -p account-upgrade up -d postgres
docker compose -p account-upgrade exec -T postgres pg_dump -U postgres -d account_upgrade --data-only `
    --exclude-table=databasechangelog --exclude-table=databasechangeloglock > account_upgrade.sql
docker compose -p account-upgrade down
Get-Content account_upgrade.sql | kubectl -n account-upgrade exec -i postgres-0 -- psql -U postgres -d account_upgrade
```

Don't roll back (`-Commit`) to a commit from before the move: its `deploy.ps1` deploys with docker compose.

## Operating it

The installed copy of the pipeline is `%LOCALAPPDATA%\account-upgrade\pipeline\bin\pipeline.ps1`; `deploy\pipeline.ps1`
in a clone works the same.

| Task | How |
|---|---|
| Deploy | Push or merge to `main`; it's live within a few minutes |
| What is deployed, last result | `pipeline.ps1 -Status` |
| Deploy now, without waiting | `pipeline.ps1` |
| Roll back / redeploy a release | `pipeline.ps1 -Commit <sha>` (rebuilds and tests that commit), or `deploy.ps1 -Tag <sha> -Registry local -SkipPull` while its images are still on the machine |
| Retry a failed commit | `pipeline.ps1 -Force` |
| Faster manual run | `-SkipTests` and/or `-SkipScan` |
| Pause / resume | `Disable-ScheduledTask AccountUpgrade-CD` / `Enable-ScheduledTask AccountUpgrade-CD` |
| Change the interval | `install-pipeline.ps1 -IntervalMinutes 5` |
| Update after changing `pipeline.ps1` | Run `install-pipeline.ps1` again (adding or removing a service does not need it: the images to build come from `docker-compose.yml`) |
| Remove | `install-pipeline.ps1 -Uninstall` |
| See what's running | `kubectl -n account-upgrade get pods,svc,jobs` |
| Logs | `kubectl -n account-upgrade logs -l app.kubernetes.io/component=backend --prefix -f` |
| Scale | `kubectl -n account-upgrade scale deployment/account-upgrade-backend --replicas 3` (until the next deployment; change `k8s/base/backend.yaml` to keep it. Keep partitions (4) >= replicas × `UPGRADE_MESSAGING_KAFKA_CONSUMER_CONCURRENCY`) |
| Connect to PostgreSQL from the host | `kubectl -n account-upgrade port-forward svc/postgres 5433:5432`, then `127.0.0.1:5433`, user `postgres`, the password from the secret |
| Validate the running deployment end to end | `cd account-upgrade-e2e; .\mvnw.cmd verify`: 45 JUnit checks (workloads ready, schema Job, Kafka topics and lag, login and tokens, eligibility rules, validation errors, idempotency, notifications, security headers, rate limit). `-DexcludedGroups=infrastructure` for a deployment without kubectl access, `-DexcludedGroups=rate-limit` to avoid the request burst. See [its README](../account-upgrade-e2e/README.md) |
| Remove the deployment | `kubectl delete namespace account-upgrade` (deletes the data volumes too) |

Run the scripts with `powershell -NoProfile -ExecutionPolicy Bypass -File <script> [options]`.

Files, all under `%LOCALAPPDATA%\account-upgrade\`:

| File | Content |
|---|---|
| `deployments.log` | One line per deployment: `DEPLOYED`, `ROLLED BACK` or `FAILED`, with the commit |
| `pipeline\logs\<time>-<sha>.log` | Full output of each run (last 50 kept) |
| `pipeline\state.json` | Deployed commit, last commit tried and its result |
| `pipeline\poll.log` | Checks that could not run (network down, Rancher Desktop or its Kubernetes not started) |

Each deployment leaves one `deploy-smoke-<timestamp>` record in the processed requests, from its smoke test.

## GitHub's part

- Pull requests: the `backend`, `frontend`, `db-schema` and `k8s-manifests` workflows check every change.
- `main`: [`publish.yml`](../.github/workflows/publish.yml) runs the checks again, then builds, scans and publishes
  the images to GHCR (`ghcr.io/<owner>/<service>:<sha>`, with SBOM and provenance) as release artifacts for other
  environments. The Rancher Desktop deployment doesn't depend on it. `deploy.ps1 -Tag <sha>` (default registry
  GHCR) deploys those images to any cluster: `-Context <context>`; the cluster needs pull access to the packages.

## Notes and limits

- **Deployment delay.** A push is picked up at the next check (default every 2 minutes). A full run takes several
  minutes, mostly the backend's integration tests.
- **Only while you're logged on.** Rancher Desktop runs in your login session, so the pipeline does too. Commits
  pushed meanwhile are deployed (the latest one) at the next check after you log on.
- **Database migrations go forward only.** The schema Job (Liquibase) migrates the schema before the backend rolls
  out. A rollback restores the previous images but not the previous schema, and during a rolling update old and
  new backend pods run side by side, so migrations must be backward compatible (expand, then contract). See
  [account-update-db-schema](../account-update-db-schema/README.md#changing-the-schema).
- **One node.** Rancher Desktop's cluster is a single node: replicas protect against a pod failing and make
  deployments seamless, not against the machine going down. PostgreSQL is a single instance.
- **Local secrets.** The secret is created by `deploy.ps1`. On a shared cluster use a secret manager (External
  Secrets, Sealed Secrets) and create `account-upgrade-secrets` from it; `deploy.ps1` leaves an existing secret alone.
- **Image retention.** The machine keeps the current, previous and 5 older releases of each image for fast rollback.

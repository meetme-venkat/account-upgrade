# Deployment to Kubernetes (Rancher Desktop)

The platform runs on Kubernetes, the k3s cluster built into Rancher Desktop. Every push to `main` is tested, built,
scanned, deployed and checked end to end by **Jenkins**, which runs in the same cluster: see
[jenkins/README.md](../jenkins/README.md) for the pipeline and how to operate it. This folder holds what gets
deployed.

| Path | Content |
|---|---|
| [`k8s/base`](k8s/base) | The Kubernetes manifests (kustomize): namespace, PostgreSQL, Kafka, schema Job, backend, frontend |
| [`../Jenkinsfile`](../Jenkinsfile) | The pipeline, including the ordered deployment and the rollback |
| [`../account-upgrade-e2e`](../account-upgrade-e2e) | End-to-end tests (JUnit) the pipeline runs after each deployment |

## What runs in the cluster

Namespace `account-upgrade` (Pod Security: `baseline` enforced):

| Workload | Kind | Replicas | Notes |
|---|---|---|---|
| `postgres` | StatefulSet | 1 | `postgres:16`, 2 Gi volume. Cluster-internal only |
| `kafka` | StatefulSet | 3 | KRaft brokers+controllers `kafka-{0,1,2}.kafka:9092`, 2 Gi volume each; topics get replication factor 3, `min.insync.replicas` 2 |
| `account-update-db-schema` | Job | 1 run | Liquibase; recreated on every deployment (a no-op when the schema is current) |
| `account-upgrade-backend` | Deployment | 2 | Service `LoadBalancer` on port **8080**; startup/liveness/readiness probes on the actuator health groups |
| `account-upgrade-frontend` | Deployment | 2 | Service `LoadBalancer` on port **4200**; proxies `/api` to the backend Service |

Rancher Desktop publishes `LoadBalancer` services on the host: UI http://127.0.0.1:4200, API http://127.0.0.1:8080
(and Jenkins on http://127.0.0.1:8090).

The images are the ones the pipeline builds into Rancher Desktop's Docker engine (`local/<service>:<commit>`), which
its Kubernetes uses directly: nothing is pushed anywhere. The pipeline pins them in a kustomize overlay it generates
at `k8s/release` (git-ignored); the base manifests only hold placeholders.

The secret `account-upgrade-secrets` (PostgreSQL password, JWT signing key) is not in the repository: the pipeline
creates it with random values on the first deployment and keeps it afterwards (PostgreSQL only reads the password
when it initialises its volume, and a new signing key would sign everyone out). To read it:

```powershell
kubectl -n account-upgrade get secret account-upgrade-secrets -o jsonpath='{.data.postgres-password}' |
    % { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) }
```

| Guarantee | How |
|---|---|
| Nothing untested is deployed | Each pipeline stage must pass before the next starts |
| Builds are reproducible | Clean workspace (`git clean -ffdx`) of exactly the commit on `main` |
| Releases are traceable | Images are tagged with the full commit SHA and labelled with it (`org.opencontainers.image.revision`); each Jenkins build is named after its commit |
| The cluster definition matches the images | The commit's own `deploy/k8s` manifests and `Jenkinsfile` are used |
| Schema before code | The schema Job runs first on every deployment; the backend is rolled out only if it succeeds, the frontend only once the backend's rollout completed. A failed migration replaces nothing and triggers the rollback |
| No downtime | 2 replicas each for backend and frontend, rolling updates with `maxUnavailable: 0` (a new pod must pass its readiness probe before an old one stops), a `preStop` pause so the Service stops routing before shutdown, graceful shutdown, PodDisruptionBudgets |
| A bad release doesn't stay live | Rollout deadlines (`progressDeadlineSeconds`), probes, the end-to-end suite after every deployment, automatic rollback (itself checked by the suite) |
| Least privilege | Jenkins deploys with a service account limited to namespace `account-upgrade` |
| Valid manifests | The `k8s-manifests` workflow renders them and validates every resource against the Kubernetes schemas on each pull request |

## Setup

Rancher Desktop with:

- **Container Engine: dockerd (moby)** (*Preferences → Container Engine*), so the cluster sees the images the
  pipeline builds;
- **Kubernetes enabled** (*Preferences → Kubernetes*), which creates the `kubectl` context `rancher-desktop`;
- at least **8 GB of memory and 4 CPUs** available to it: the platform requests about 2.5 GB (3 Kafka brokers,
  2 backends, PostgreSQL, 2 frontends), Jenkins up to 4 GB while it builds, and k3s needs some too. On Windows it
  runs in WSL, which gets half the host's memory by default (limit it in `%USERPROFILE%\.wslconfig`, not in Rancher
  Desktop); on macOS and Linux set it in *Preferences → Virtual Machine*.

The command line equivalent of the first two settings, which also starts Rancher Desktop:

```powershell
rdctl start --kubernetes.enabled=true --container-engine.name=moby
```

If `docker` then fails with `timed out dialing Hyper-V socket` while Kubernetes works, restart Rancher Desktop
(`rdctl shutdown`, then the command above).

Then install Jenkins ([jenkins/README.md](../jenkins/README.md#setup)); its first build deploys `main`.

### Moving from the docker compose deployment

Earlier releases ran as the compose project `account-upgrade`, which publishes the same ports. Stop it once:

```powershell
docker compose -p account-upgrade down      # keeps its volume account-upgrade_postgres-data; -v would delete it
```

The Kubernetes deployment starts with an empty database. To carry the old data over, dump it from the compose
volume and restore it into the new PostgreSQL after the first deployment:

```powershell
docker compose -p account-upgrade up -d postgres
docker compose -p account-upgrade exec -T postgres pg_dump -U postgres -d account_upgrade --data-only `
    --exclude-table=databasechangelog --exclude-table=databasechangeloglock > account_upgrade.sql
docker compose -p account-upgrade down
Get-Content account_upgrade.sql | kubectl -n account-upgrade exec -i postgres-0 -- psql -U postgres -d account_upgrade
```

Don't redeploy (`COMMIT=...`) a commit from before the move to Kubernetes: it has no `deploy/k8s`.

## Operating the deployment

Deploying, rolling back and the build history are in Jenkins ([jenkins/README.md](../jenkins/README.md#operating-it)).

| Task | How |
|---|---|
| See what's running | `kubectl -n account-upgrade get pods,svc,jobs` |
| Logs | `kubectl -n account-upgrade logs -l app.kubernetes.io/component=backend --prefix -f` |
| Scale | `kubectl -n account-upgrade scale deployment/account-upgrade-backend --replicas 3` (until the next deployment; change `k8s/base/backend.yaml` to keep it. Keep partitions (4) >= replicas × `UPGRADE_MESSAGING_KAFKA_CONSUMER_CONCURRENCY`) |
| Connect to PostgreSQL from the host | `kubectl -n account-upgrade port-forward svc/postgres 5433:5432`, then `127.0.0.1:5433`, user `postgres`, the password from the secret |
| Check the deployment end to end, from the host | `cd account-upgrade-e2e; .\mvnw.cmd verify`: 45 JUnit checks, including the rate limit the pipeline leaves out. See [its README](../account-upgrade-e2e/README.md) |
| Remove the deployment | `kubectl delete namespace account-upgrade` (deletes the data volumes too) |

Each end-to-end run leaves its `e2e-<timestamp>-*` requests in the processed requests.

## GitHub's part

- Pull requests: the `backend`, `frontend`, `db-schema`, `e2e` and `k8s-manifests` workflows check every change.
- `main`: [`publish.yml`](../.github/workflows/publish.yml) runs the checks again, then builds, scans and publishes
  the images to GHCR (`ghcr.io/<owner>/<service>:<sha>`, with SBOM and provenance) as release artifacts for other
  environments. The local deployment doesn't depend on it.

## Notes and limits

- **Deployment delay.** Jenkins polls `main` every 2 minutes. A full run takes several minutes, mostly the backend's
  integration tests.
- **Only while Rancher Desktop runs.** Jenkins runs in its cluster; commits pushed meanwhile are built (the latest
  one) at the first poll after it starts.
- **Database migrations go forward only.** The schema Job (Liquibase) migrates the schema before the backend rolls
  out. A rollback restores the previous images but not the previous schema, and during a rolling update old and
  new backend pods run side by side, so migrations must be backward compatible (expand, then contract). See
  [account-update-db-schema](../account-update-db-schema/README.md#changing-the-schema).
- **One node.** Rancher Desktop's cluster is a single node: replicas protect against a pod failing and make
  deployments seamless, not against the machine going down. PostgreSQL is a single instance.
- **Local secrets.** The secret is created by the pipeline. On a shared cluster use a secret manager (External
  Secrets, Sealed Secrets) and create `account-upgrade-secrets` from it; the pipeline leaves an existing secret alone.
- **Image retention.** The node keeps the current, previous and 5 older releases of each image for fast rollback.

# Continuous delivery to Rancher Desktop

Every push to `main` is tested, built, scanned, published and deployed to the Docker engine of Rancher Desktop on
the deployment machine. The pipeline is [`.github/workflows/cd.yml`](../.github/workflows/cd.yml); the deployment
itself is [`deploy.ps1`](deploy.ps1).

```
push to main
  ├─ backend CI  (backend.yml: tests on Java 17 + 21, coverage gate, enforcer, prod image end to end)
  └─ frontend CI (frontend.yml: format, tests + coverage gate, budgeted build, audit, image checks)
        │ both green
        ▼
  build (GitHub-hosted, per service)
    buildx build (layer cache) ─► Trivy scan: no fixable CRITICAL ─► push to GHCR with SBOM + provenance
      ghcr.io/<owner>/account-upgrade-backend:<commit sha>   (+ :main)
      ghcr.io/<owner>/account-upgrade-frontend:<commit sha>  (+ :main)
        │
        ▼
  deploy (self-hosted runner on the Rancher Desktop machine, environment "rancher-desktop")
    checkout that commit ─► deploy.ps1:
      pull images ─► docker compose up --wait (all health checks) ─► smoke test
        │ any failure
        └─► roll back to the images that were running, job fails
```

| Guarantee | How |
|---|---|
| Nothing untested is deployed | The deploy job needs both CI workflows and the build to pass |
| Nothing with a known fixable critical CVE is published | Trivy gate before push |
| Immutable, traceable releases | Images are tagged with the full commit SHA and carry an SBOM and build provenance |
| The stack definition matches the images | The deploy job checks out the released commit's `docker-compose.yml` |
| One deployment at a time, never cut off halfway | `concurrency: cd-main`, `cancel-in-progress: false` |
| A bad release doesn't stay live | `compose up --wait` + smoke test (API → Kafka → PostgreSQL → email outbox, and the UI's `/api` proxy); on failure, automatic rollback |
| Deployments are auditable | GitHub environment history, job summary, and `%LOCALAPPDATA%\account-upgrade\deployments.log` on the machine |

## One-time setup

### 1. Protect the repository (important: the repository is public)

A self-hosted runner executes whatever a workflow tells it to. On a public repository, a pull request from a fork
could otherwise run code on this machine.

- **Settings → Actions → General → Approval for running fork pull request workflows from contributors:** choose
  *Require approval for all external contributors*. Never approve a fork PR that edits `.github/workflows/`
  without reading it.
- **Settings → Environments → New environment** `rancher-desktop`:
  - *Deployment branches and tags*: **Selected branches** → `main`. Only `main` can deploy.
  - Optional: *Required reviewers* to approve each deployment by hand.
- **Settings → Branches:** protect `main` and require the `backend` and `frontend` checks on pull requests.

### 2. Install the self-hosted runner on the Rancher Desktop machine

Rancher Desktop must use the **dockerd (moby)** engine (*Preferences → Container Engine*).

1. **Settings → Actions → Runners → New self-hosted runner → Windows, x64.** Copy the token it shows.
2. In PowerShell, outside the repository clone:
   ```powershell
   mkdir C:\actions-runner; cd C:\actions-runner
   # Download and extract the runner zip with the commands GitHub shows on that page, then:
   .\config.cmd --url https://github.com/meetme-venkat/account-upgrade --token <TOKEN> `
       --name rancher-desktop-$env:COMPUTERNAME --labels rancher-desktop --work _work
   ```
3. Run it as **your own Windows account**, which is the one that can reach Rancher Desktop's Docker engine and has
   `docker` on its `PATH`. Pick one:
   - Interactive: `.\run.cmd` in a terminal you leave open.
   - Windows service: answer **Y** to *run as service* during `config.cmd` and give your own account, not the
     default `NT AUTHORITY\NETWORK SERVICE` (that account cannot use Docker).

The runner shows as *Idle* under **Settings → Actions → Runners**. Rancher Desktop runs in your login session, so
deployments happen while you are logged in with Rancher Desktop started. A deployment that finds no runner waits in
the queue, and gives up after 24 hours.

### 3. First deployment

The pipeline owns one compose stack, the project `account-upgrade`. Stop any stack started by hand from a clone,
because it holds ports 8080 and 4200 (the deployment refuses to start otherwise and names the containers):

```powershell
docker compose -p <project> down      # e.g. -p mercur for a clone in D:\MERCUR. Add -v to also delete its data
```

Then push to `main`, or run the **cd** workflow from the Actions tab.

## Operating it

| Task | How |
|---|---|
| Deploy | Push or merge to `main` |
| Roll back / redeploy a release | Actions → **cd** → *Run workflow* on `main`, `image_tag` = that release's full commit SHA. Tests and build are skipped |
| Roll back by hand | `powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Tag <sha>` (after `docker login ghcr.io`) |
| Deploy local images without a registry | See the example at the top of `deploy.ps1` (`-Registry local -SkipPull`) |
| See what's running | `docker compose -p account-upgrade ps` |
| Deployment history | Repository → *Environments* → `rancher-desktop`, or `%LOCALAPPDATA%\account-upgrade\deployments.log` |

Endpoints: UI http://127.0.0.1:4200, API http://127.0.0.1:8080. Each deployment leaves one `deploy-smoke-<timestamp>`
record in the processed requests, from its smoke test.

## Notes and limits

- **Database migrations go forward only.** Flyway runs at backend startup. A rollback restores the previous images
  but not the previous schema, so keep migrations backward compatible (expand, then contract) for rollback to be safe.
- **Short downtime on each deployment.** One backend and one frontend container are replaced in place. Zero-downtime
  rollouts need several replicas behind a load balancer, which means Kubernetes (Rancher Desktop includes k3s).
- **Image retention.** The machine keeps the current, previous and 5 older releases of each image for fast rollback.
  GHCR keeps every release; add a retention policy there if storage matters.
- **Pinning.** Actions are referenced by major version and kept current by Dependabot. For a stricter supply chain,
  pin them to commit SHAs.

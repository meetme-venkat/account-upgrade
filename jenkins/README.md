# Jenkins: CI/CD for the Account Upgrade platform

Jenkins runs inside the local Kubernetes cluster (Rancher Desktop's k3s) and runs the repository's
[`Jenkinsfile`](../Jenkinsfile) for every new commit on `main`:

```
poll main every 2 minutes ─► new commit?
  checkout            clean workspace (git clean -ffdx), exactly that commit
  backend tests       mvnw verify: unit + integration tests (Testcontainers), coverage gate, enforcer; results per test
  build images        local/account-update-db-schema, account-upgrade-{backend,frontend}:<commit>
                      (the frontend's tests and budgeted production build run inside its image build)
  vulnerability scan  Trivy: no fixable CRITICAL vulnerability
  deploy              kubectl apply, one component at a time (namespace account-upgrade):
                        postgres, kafka (ready) ─► schema Job (must succeed) ─► backend ─► frontend (rolled out)
  end-to-end tests    account-upgrade-e2e (JUnit) against the cluster's Services; results per test
    └─ deploy or e2e failure: the images that were running before are deployed again and tested
```

Everything is defined in the repository: the pipeline (`Jenkinsfile`), Jenkins itself (`Dockerfile`, `plugins.txt`),
its configuration including the job (`casc.yaml`, applied at every start) and its deployment (`k8s/`). Nothing is
set up by hand in the UI.

| File | Content |
|---|---|
| [`Dockerfile`](Dockerfile) | Jenkins LTS (JDK 21) plus the Docker CLI with buildx and kubectl |
| [`plugins.txt`](plugins.txt) | Plugins: configuration as code, Job DSL, pipeline, git, JUnit, … |
| [`casc.yaml`](casc.yaml) | Security (one admin user), the `account-upgrade` pipeline job and its 2-minute polling |
| [`k8s/`](k8s) | Namespace `jenkins`, the controller (StatefulSet, 20 Gi home volume, UI on port 8090), RBAC |

## How it fits in the cluster

- **Builds use the node's Docker engine** through its socket (`/var/run/docker.sock`). Rancher Desktop's Kubernetes
  runs images from that same engine, so the pipeline's images are deployable without a registry.
- **Deployments use the pod's service account** (`jenkins`), which may manage namespace `account-upgrade` and nothing
  else (see [`k8s/rbac.yaml`](k8s/rbac.yaml)). No kubeconfig or cluster credential is stored in Jenkins.
- **The backend's tests** start PostgreSQL and Kafka with Testcontainers on the node's engine;
  `TESTCONTAINERS_HOST_OVERRIDE` (the node's IP) lets them reach the containers' ports.
- **The end-to-end tests** call the Services by their cluster DNS names and use kubectl with the service account
  (`-De2e.kubeContext=in-cluster`). The rate-limit burst is left out of the pipeline run.
- **Mounting the Docker socket makes Jenkins as powerful as the node.** Acceptable for a single-user local machine;
  on a shared or cloud cluster, build with agents (Kaniko or BuildKit pods) and push to a registry instead.

## Setup

Requirements: Rancher Desktop with Kubernetes enabled and the dockerd (moby) engine
(see [deploy/README.md](../deploy/README.md#setup)).

```powershell
# 1. The Jenkins image
docker build -t local/account-upgrade-jenkins:2.568.3 jenkins

# 2. The administrator's password (choose one), kept in the cluster only
kubectl --context rancher-desktop apply -f jenkins/k8s/namespace.yaml
kubectl --context rancher-desktop -n jenkins create secret generic jenkins-admin --from-literal=password=<password>

# 3. Jenkins (needs the namespace account-upgrade to exist: kubectl apply -f deploy/k8s/base/namespace.yaml)
kubectl --context rancher-desktop apply -k jenkins/k8s
kubectl --context rancher-desktop -n jenkins rollout status statefulset/jenkins
```

Then open http://127.0.0.1:8090 and sign in as `admin`. The first poll starts a build of `main` within 2 minutes
(or: job `account-upgrade` → *Build with Parameters*).

To read the password back:

```powershell
kubectl --context rancher-desktop -n jenkins get secret jenkins-admin -o jsonpath='{.data.password}' |
    % { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) }
```

## Operating it

| Task | How |
|---|---|
| Deploy | Push or merge to `main`; it's live within minutes |
| What is deployed, history | The job's build list: each build is named after its commit, and its description says `Deployed`, `ROLLED BACK` or `FAILED` |
| Test results | Each build's *Tests* page: backend and end-to-end tests, per test |
| Deploy now / redeploy / roll back | *Build with Parameters*; `COMMIT=<sha>` deploys that commit (it must contain `deploy/k8s`) |
| Faster manual run | `SKIP_TESTS` and/or `SKIP_SCAN` |
| Pause / resume | The job's *Disable Project* / *Enable* |
| Build another branch | Change `PIPELINE_BRANCH` in [`k8s/kustomization.yaml`](k8s/kustomization.yaml), then `kubectl apply -k jenkins/k8s`, **then start one build by hand** (*Build Now*): polling keeps watching the branch the last build checked out until a build has run from the new one |
| Change the admin password | Update the secret, **then**, once that has returned, restart (Jenkins reads it only at start): `kubectl -n jenkins create secret generic jenkins-admin --from-literal=password=<new> --dry-run=client -o yaml \| kubectl apply -f -`, then `kubectl -n jenkins delete pod jenkins-0`. A restart interrupts a running build |
| Change the job or security | Edit `casc.yaml`, rebuild the image (step 1), restart: `kubectl -n jenkins delete pod jenkins-0` |
| Update Jenkins or its plugins | Change the version in `Dockerfile` (and in `k8s/jenkins.yaml`), rebuild, apply. Plugins resolve to their latest compatible versions at every image build |
| Logs of Jenkins itself | `kubectl -n jenkins logs jenkins-0` |
| Remove | `kubectl delete -k jenkins/k8s` (deletes the build history with the namespace's volume) |

Builds run on the controller (2 executors, one build of this job at a time). The home volume keeps the job history,
the last 50 builds and the Maven cache, so builds after the first don't download dependencies again.

## Moving to AWS

The `Jenkinsfile` is portable Linux shell and kubectl. For EKS, the parts that are specific to this machine change:
images are built by Kaniko or BuildKit agent pods (no Docker socket on EKS nodes) and pushed to ECR (`REGISTRY`), and
the pipeline deploys with an IAM role for the service account (IRSA) instead of an in-cluster service account.

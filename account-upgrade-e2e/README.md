# account-upgrade-e2e

End-to-end tests of a **running** Account Upgrade deployment, in JUnit. They use only what a client or an operator
sees: the public HTTP API (through the UI's reverse proxy, as a browser does) and, for the infrastructure checks,
`kubectl`. No Spring, no dependency on the services' code.

```powershell
cd account-upgrade-e2e
.\mvnw.cmd verify                                   # Windows; ./mvnw verify elsewhere. Needs a JDK 17+
```

Defaults target the local Kubernetes deployment (`deploy/README.md`): UI `http://127.0.0.1:4200`, API
`http://127.0.0.1:8080`, kube context `rancher-desktop`, namespace `account-upgrade`.

| Section (test class) | Checks |
|---|---|
| Infrastructure (`InfrastructureTest`, tag `infrastructure`) | postgres 1/1, kafka 3/3, backend 2/2, frontend 2/2 pods ready; schema Job succeeded; changelog applied; application tables exist; both topics have replication factor 3; dead-letter queue empty; consumer lag 0 |
| Authentication | no token 401; wrong password 401; login returns a Bearer token that opens the API; tampered token 401; health public |
| Ingestion | batch of 7 accepted (202); real-time accepted; a request retried with the same `Idempotency-Key` accepted each time |
| Validation errors | missing userId, invalid parentEmail, empty batch, malformed JSON, unknown field: 400 each |
| Processing | all 9 requests processed and notified (Kafka, eligibility, PostgreSQL, outbox); age 18/23 and $30 eligible, age 17/24 and $29.99 not; all 3 reasons recorded; the idempotent request stored once; status and userId filters |
| Notifications | one email per user; a parent email only for the eligible user with a parent; the decline email gives the reason |
| Guardrails | UI sign-in page and its security headers; rate limit throttles a 200-request burst before authentication (tag `rate-limit`) |

The sections run in that order; the rate-limit burst last, since it briefly throttles this machine. Each run submits
its own requests (user ids `e2e-<timestamp>-*`), which stay in the database like any other request.

## Settings

Each is a system property (`-De2e.baseUrl=...`), else the environment variable, else the default.

| Property | Environment variable | Default |
|---|---|---|
| `e2e.baseUrl` | `E2E_BASE_URL` | `http://127.0.0.1:4200` (the UI; the backend directly also works) |
| `e2e.apiUrl` | `E2E_API_URL` | `http://127.0.0.1:8080` (the backend, for the rate-limit check) |
| `e2e.adminUsername` / `e2e.adminPassword` | `UPGRADE_SECURITY_ADMIN_USERNAME` / `_PASSWORD` | `admin` / `admin` |
| `e2e.kubeContext` | `ACCOUNT_UPGRADE_KUBE_CONTEXT` | `rancher-desktop`; `in-cluster` in a pod (the pod's service account, as in the Jenkins pipeline) |
| `e2e.namespace` | `E2E_NAMESPACE` | `account-upgrade` |
| `e2e.topic` | `UPGRADE_MESSAGING_TOPICS_UPGRADE_REQUESTS` | `upgrade-requests` |
| `e2e.processingTimeout` (seconds) | `E2E_PROCESSING_TIMEOUT` | `60` |

Leave out sections by tag: `-DexcludedGroups=infrastructure` (a deployment without `kubectl` access, e.g. the docker
compose stack), `-DexcludedGroups=rate-limit`, or both: `-DexcludedGroups=infrastructure,rate-limit`.
Results: `target/surefire-reports`.

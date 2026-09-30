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

## Corner cases and soak

Two heavier suites run only on demand, never in the default run or the Jenkins pipeline. They replace the former
`account-upgrade-backend/scripts/corner-cases.ps1` and `soak.ps1`, and like the infrastructure checks they need
`kubectl` access (counts come from PostgreSQL and Kafka). They call the backend directly (`e2e.apiUrl`) and retry a
throttled call (429) after its `Retry-After`, as a well-behaved client does.

```powershell
.\mvnw.cmd verify -Pcorner-cases                         # 65 cases, about a minute
.\mvnw.cmd verify -Psoak "-De2e.soak.note=after X"       # 10 x 10,000 requests by default
.\mvnw.cmd verify -Psoak "-De2e.soak.batches=2" "-De2e.soak.batchSize=2000"   # a quick one
```

| Suite | What it checks | Output |
|---|---|---|
| `CornerCasesTest` | **Validation** (18: missing/blank userId, bad email, malformed or empty body, wrong types, array/object mix-ups, empty and over-10,000 batches, wrong content type/method/path, bad status filters, unknown fields); **eligibility** boundaries (24: ages 0/17/18/23/24/200/-5/null/20.9, balances 0/-100/29.99/29.999/30/30.00/huge/"45.5"/null, empty/blank/null names, all rules failing, unicode); **notifications** (parent only when eligible and given; decline email lists every reason); **duplicates and batches** (distinct event ids, same-user order kept, receipts in input order, nothing stored from rejected calls); **load** (a 5,000 batch and 300 concurrent requests, all processed, the expected 1,500/3,500 outcome split); **query** filters, ordering and health | [`test-results/corner-cases.md`](test-results/corner-cases.md), one row per case, rewritten every run |
| `SoakTest` | Batches sent as fast as they are accepted; every accepted request processed (none lost), no dead letters. Records throughput, how many are notified, and each backend pod's JVM threads and heap before and after | A row appended to [`test-results/soak-history.md`](test-results/soak-history.md) |

| Setting | Default |
|---|---|
| `e2e.soak.batches` / `e2e.soak.batchSize` | `10` / `10000` (the API's maximum batch) |
| `e2e.soak.timeout` (seconds to wait for processing) | `900` |
| `e2e.soak.note` | free text for the history row |
| `e2e.loadTimeout` (corner cases' load group, seconds) | `180` |

Differences from the former scripts, which ran against a single process with the earlier in-memory broker:

- **No fresh app needed.** Each run has its own user-id prefix (`cc-<timestamp>-`, `soak-<timestamp>-`) and counts only
  its own records, so it runs against a deployment with any history. The records stay in the database.
- **Authentication and rate limiting.** Calls carry the admin's access token. The concurrent burst shows the rate
  limit (some 429s) and then retries those, checking all 300 get through; at most 100 are in flight at once, because
  Rancher Desktop's port forwarding refuses a burst of 300 new connections before they reach the application.
- **Strict input.** An unknown JSON field is rejected with 400 by the prod profile (it used to be ignored with 202).
- **Metrics.** Processed, notified and dead-lettered counts come from PostgreSQL and Kafka, which cover every backend
  pod; threads and heap are per pod (`jvm.threads.live`, `jvm.memory.used`) instead of the OS process.

# Monitoring: Prometheus, Loki and Grafana

Live metrics, searchable logs and one dashboard for the platform, in the same cluster. Everything here is open source and free; the
only cost is the cluster capacity it uses.

## What runs

| Component | Namespace | Does | Resources (request / limit) |
|---|---|---|---|
| Prometheus `v3.5.0` | `monitoring` | Scrapes every 15 s, keeps 7 days (at most 4 GB) on a 5 Gi volume | 256 / 768 MiB |
| Loki `3.5.3` | `monitoring` | Stores the logs of every pod in `account-upgrade` for 7 days on a 5 Gi volume | 256 / 768 MiB |
| Alloy `v1.10.2` | `monitoring` | Ships those logs to Loki, reading them through the Kubernetes API | 96 / 384 MiB |
| Grafana `12.1.1` | `monitoring` | The "Account Upgrade" dashboard and log search, on http://127.0.0.1:3000 | 128 / 384 MiB |
| kafka-exporter `v1.9.0` | `account-upgrade` | Topic offsets and consumer-group lag | 32 / 96 MiB |
| postgres-exporter `v0.17.1` | `account-upgrade` | Transactions, connections, rows written | 32 / 96 MiB |

Prometheus scrapes:

- **Each backend pod**, at `/actuator/prometheus`. It finds the pods through the headless Service
  `account-upgrade-backend-metrics`, so it needs no Kubernetes API permissions.
- **The two exporters.** They run next to what they watch: the brokers' advertised names (`kafka-0.kafka`) resolve only
  in that namespace, and the database password is the application's own secret.

## Install or update

With the application already deployed by Jenkins:

```sh
deploy/k8s/monitoring/install.sh          # bash, Git Bash or Linux; idempotent
```

It creates the Grafana admin password once, randomly, in the secret `grafana-admin`, and never prints it. To read it:

```sh
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
```

Log in to http://127.0.0.1:3000 as `admin`: the dashboard is the home page. To query Prometheus directly, run
`kubectl -n monitoring port-forward svc/prometheus 9090` and open http://127.0.0.1:9090 (Status → Targets shows
what is scraped). To remove it all: `kubectl kustomize deploy/k8s/monitoring | kubectl delete -f -`. That also
deletes the `monitoring` namespace and the stored metrics.

## The dashboard

The dashboard is provisioned from [`dashboards/account-upgrade.json`](dashboards/account-upgrade.json).

- **Changing it:** edit that file and re-run `install.sh`. Changes made in the UI are not kept.
- **Your own dashboards:** you can still create them in the UI, but Grafana keeps no volume, so they are lost when
  its pod restarts. Export them to this directory to keep them.

| Row | Panels |
|---|---|
| Now | Requests accepted/s, decisions/s, Kafka backlog, emails waiting, emails failed, dead letters in the last hour |
| Ingestion | API calls/s by endpoint and status (including 429s from the rate limiter); ingestion latency p50/p95/p99 |
| Processing | Accepted vs decided/s (by status); backlog per partition; decisions/s per pod |
| Notifications | Emails sent/s per pod; emails waiting and failed |
| Database | Connection pool per pod (active, pending, size); PostgreSQL commits and rollbacks/s; connections and rows written/s |
| Backend JVM | CPU, heap, threads and GC pause time per pod |
| Logs | Backend log lines/s by level; backend warnings and errors; logs of everything else (Kafka, PostgreSQL, frontend, jobs) |

The metrics come from the backend (`/actuator/prometheus`):

| Metric | Meaning |
|---|---|
| `upgrade_decisions_total{status}` | Decisions stored by the pod, counted after their transaction commits |
| `upgrade_notifications_sent_total` | Emails delivered by the pod's relay |
| `upgrade_notifications_pending`, `upgrade_notifications_failed` | Outbox rows waiting, or out of attempts. Every pod reports the same database value, so the dashboard takes the max. |
| `upgrade_dead_letters_total` | Events sent to the dead-letter topic |
| `http_server_requests_seconds_*` | Request count and latency buckets |
| `hikaricp_*`, `jvm_*` | Built-in connection-pool and JVM metrics |

`upgrade_processed` (all stored decisions) is kept for `/actuator/metrics`, but counting the table costs ~110 ms at
2 million rows. The dashboard uses the counter instead.

## Logs

In Kubernetes the backend logs one JSON object per line, in the Elastic Common Schema (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` in
[backend.yaml](../base/backend.yaml)). The request id is a field (`requestId`), so logs can be searched by request:

**How logs reach Loki**
- **Collection:** Alloy follows the logs of every pod in `account-upgrade` through the Kubernetes API. That needs no
  access to the nodes, and its permissions are a Role in that one namespace: list pods and read their logs.
- **Labels:** each line is labelled with `namespace`, `app`, `component`, `pod` and `container`. Backend lines also
  get `level` (INFO, WARN, ERROR), so errors can be selected without parsing every line.
- **Resuming:** Alloy records how far it has read each pod. After a restart it continues from there instead of
  re-sending.

**Searching.** In Grafana, open **Explore**, choose **Loki**, then query:

```
{app="account-upgrade-backend", level="ERROR"}                               errors from any backend pod
{app="account-upgrade-backend"} | json | requestId="<id>"                    one request, across pods
{app="account-upgrade-backend"} | json | log_logger=~".*OutboxRelay"         the email relay
sum by (level) (rate({app="account-upgrade-backend"}[1m]))                   log lines/s by level
{app="kafka"} |= "ERROR"                                                     broker errors
```

`| json` turns every field of a backend line into a label for that query. Examples: `log_logger`,
`process_thread_name`, `message`, `requestId`.

**Volume.** The backend logs one line per email sent, so a 100,000-request run writes about 250,000 lines. Loki's
limits are raised for that (16 MB/s). In production, lowering `EmailChannelImpl`'s logger to WARN would remove
most of the volume.

## On AWS (EKS)

The manifests run unchanged. The software costs nothing, but its infrastructure does:

- **Node capacity:** about 1.5–2.5 GiB of memory and 0.3 vCPU.
- **Storage:** EBS volumes for Prometheus and Loki (5 Gi each). Loki is normally pointed at an S3 bucket instead
  (`object_store: s3`), which is cheaper per GB and removes the volume.
- **Grafana's Service:** change it to `ClusterIP` and use `kubectl port-forward` or an internal ingress. As a
  `LoadBalancer` it would create a public, billed load balancer.
- **Database user:** give postgres-exporter its own database role with only `pg_monitor` (a schema changeset) instead
  of the application's user.

Managed alternatives, if you'd rather not run Prometheus and Grafana yourself:

- **Amazon Managed Service for Prometheus** plus **Amazon Managed Grafana:** paid by usage and per user.
- **Grafana Cloud's free tier:** within its limits, but the metrics leave AWS.

Both work with the same `/actuator/prometheus` endpoint.

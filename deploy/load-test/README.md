# Peak load test: 10,000 real-time requests

> The same test at 100,000 requests, with its own before and after: [peak-100k.md](peak-100k.md); and batching the
> consumers' database writes, measured the same way: [batch-writes.md](batch-writes.md); and the email relay alone:
> [relay-throughput.md](relay-throughput.md).

**Question.** What happens when 10,000 upgrade requests arrive at once, each as its own real-time call, and what
should be sized for it: Kafka, the database connections, consumer threads, or pods?

**Answer, measured on 2026-09-30.**

- **Accepted without errors.** 10,000 requests were all accepted in 2.2–3.6 s when the pods were warm (up to 5.8 s right after a restart), 2,800–4,500 requests/s, median
  latency ~45 ms, p95 ~90 ms. There were no errors, no lost requests and no dead letters, in any of the
  11 runs.
- **Drained in seconds.** The backlog queued in Kafka and was processed and notified **~12 s** after the first
  request with 4 consumers, and **~9 s** with 8 consumers.
- **Database connections are not the limit.** At most 7 of the 20 connections were ever busy.
- **Memory headroom: raised, but less critical than first reported.** `kubectl top` showed PostgreSQL at 98.6 % of its
  limit and a Kafka broker above Kafka's old limit. The 100,000-request test later showed that this figure is mostly
  file cache, which the kernel frees before killing a container (see the correction under Findings).

| | Before | After |
|---|---|---|
| Kafka memory limit | 640 MiB | **1 GiB** |
| PostgreSQL memory limit | 512 MiB | **1 GiB** |
| Topic partitions (`UPGRADE_MESSAGING_PARTITIONS`) | 4 | **8** |
| Consumer threads per pod (`UPGRADE_MESSAGING_KAFKA_CONSUMER_CONCURRENCY`) | 2 (4 consumers) | **4 (8 consumers)** |
| Database connections per pod (`maximum-pool-size`) | 10 | 10 (unchanged) |
| Backend pods | 2 | 2 (unchanged) |

## How the test works

```
k6 Job (in the cluster, 200 concurrent clients) ──► backend Service ──► Kafka ──► consumers ──► PostgreSQL
                 10,000 × POST /api/realtime-upgrade                                  (decision + email outbox)
```

- **Inside the cluster.** [`peak.js`](peak.js) runs as a Kubernetes Job ([`job.yaml`](job.yaml)). The host's port
  forwarding refuses bursts of new connections, so a test from the host would measure that instead of the service.
- **The requests.** 200 concurrent clients share 10,000 requests. Each is one real-time call, logged in with the
  admin's token.
  - The mix is 30 % eligible (age 16–25 × balance $25–34).
  - Every other request has a parent email, so processing and the email outbox do realistic work.
- **The runner.** [`run.sh`](run.sh) starts the Job and samples every ~3 s until every request is processed and
  notified. Each sample records:
  - processed count and unsent emails, from PostgreSQL;
  - busy and total database connections;
  - CPU and memory of every pod.

  Afterwards it records k6's summary, the exact seconds from the first request to the last accepted, last processed
  and last email sent (from the database timestamps), the eligible/ineligible split, and dead letters added.
- **The rate limiter was off during the runs.** It allows each client 20 requests/s per backend pod, so with it on,
  a single load generator measures only the limiter. That is correct behaviour for a real client (the e2e suite
  checks it), not the service's capacity. It was switched back on afterwards, and the e2e suite passed 45/45,
  including the rate-limit check.

To run it (from the repository root; bash, Git Bash or Linux, with kubectl access):

```sh
kubectl -n account-upgrade set env deployment/account-upgrade-backend UPGRADE_GUARDRAILS_RATE_LIMIT_ENABLED=false
deploy/load-test/run.sh my-label                 # REQUESTS=10000 by default; results in deploy/load-test/results/
kubectl -n account-upgrade set env deployment/account-upgrade-backend UPGRADE_GUARDRAILS_RATE_LIMIT_ENABLED-
```

Changing an environment variable restarts the backend pods. The first run afterwards is slower (JVM warm-up), so do
one warm-up run before measuring.

## Evidence

All raw data (configuration, k6 summary, per-3-s timeline, result) is in [`results/`](results), one folder per run.

### 1. Original configuration (before)

Kafka 640 MiB, PostgreSQL 512 MiB, 4 partitions, 2 threads per pod.

| Run | Accept 10,000 (s) | Requests/s | Median / p95 / p99 latency (ms) | Not accepted | Last processed (s) | Last email (s) | Dead letters |
|---|---|---|---|---|---|---|---|
| before-1 (cold) | 3.6 | 2,805 | 58 / 159 / 218 | 0 | 14.1 | 14.1 | 0 |
| before-2 | 2.5 | 4,031 | 41 / 99 / 168 | 0 | 12.2 | 12.4 | 0 |

**Peak memory against the limit:**
- PostgreSQL **505 MiB of 512 MiB (98.6 %)**, as `kubectl top` reports it, file cache included (see the correction in Findings)
- Kafka 571 MiB of 640 MiB (89 %)

**Busy database connections:** at most 2–4 of 21.

### 2. New configuration (after)

Kafka 1 GiB, PostgreSQL 1 GiB, 8 partitions, 4 threads per pod, deployed through Jenkins (build 16): 8 consumers, one
partition each.

| Run | Accept 10,000 (s) | Requests/s | Median / p95 / p99 latency (ms) | Not accepted | Last processed (s) | Last email (s) | Dead letters |
|---|---|---|---|---|---|---|---|
| after-1 (cold) | 5.8 | 1,711 | 105 / 202 / 369 | 0 | 15.4 | 15.6 | 0 |
| after-2 | 3.2 | 3,162 | 57 / 115 / 143 | 0 | 9.3 | 9.4 | 0 |
| after-3 | 2.8 | 3,540 | 50 / 90 / 115 | 0 | 8.4 | 8.9 | 0 |
| after-4 | 2.6 | 3,788 | 46 / 94 / 131 | 0 | 9.1 | 9.5 | 0 |
| after-5 | 2.3 | 4,258 | 42 / 80 / 108 | 0 | 11.5 | 11.8 | 0 |

**Peak memory against the limit:**
- Kafka **657 MiB, above the old 640 MiB limit**, now 64 % of 1 GiB (file cache included)
- PostgreSQL 420 MiB of 1 GiB (41 %; its cache was still refilling after its restart)

**Busy database connections:** at most 5–7 of 21.

### 3. Controlled comparison: 4 vs 8 consumers, everything else equal

Runs on different days, or right after a restart, vary a lot, so the effect of the consumer count was measured on the
same deployment: the new configuration with 2 threads per pod set temporarily, after one discarded warm-up run.

| Run (4 consumers) | Accept 10,000 (s) | Requests/s | Median / p95 / p99 latency (ms) | Last processed (s) | Last email (s) | Dead letters |
|---|---|---|---|---|---|---|
| warm-up (cold, discarded) | 4.9 | 2,056 | 72 / 192 / 558 | 16.3 | 16.4 | 0 |
| consumers4-1 | 3.3 | 2,995 | 57 / 134 / 178 | 12.9 | 13.5 | 0 |
| consumers4-2 | 2.3 | 4,262 | 41 / 83 / 115 | 11.2 | 11.3 | 0 |
| consumers4-3 | 2.2 | 4,471 | 37 / 75 / 101 | 10.9 | 11.0 | 0 |

**Summary of warm runs.** 4 consumers: before-2 and consumers4-1..3. 8 consumers: after-2..5. Four runs each.

| | 4 consumers | 8 consumers | Change |
|---|---|---|---|
| Last request processed, median (range) | **11.7 s** (10.9–12.9) | **9.2 s** (8.4–11.5) | **−21 %** |
| Processing throughput, median | ~855 events/s | ~1,090 events/s | +27 % |
| Accepting, median requests/s | ~4,150 | ~3,660 | within run-to-run noise |
| p95 latency, median | ~91 ms | ~92 ms | same |
| Busy database connections, peak | 3–4 | 5–7 | of 10 per pod: never exhausted |
| Errors, lost requests, dead letters | 0 | 0 | |

## Findings

1. **Kafka absorbs the peak; nothing needs to scale out for it.** Accepting a request only writes it to Kafka and
   waits for its acknowledgement. The database is not involved. 10,000 requests were accepted in about 3 s. The
   backlog waited in Kafka and was processed within ~12 s (~9 s after the change). Nothing was lost or dead-lettered.
2. **Database connections were never the bottleneck.** At most 7 of 20 were busy, even with 8 consumers. Each consumer
   uses one connection at a time, so the rule is **consumer threads per pod < connections per pod**. More connections
   would not have helped.
3. **Memory headroom: raised to 1 GiB. Correction: this was less of a risk than first stated.** This finding first
   said PostgreSQL (98.6 %) and Kafka (above 640 MiB) were about to be killed. The 100,000-request test
   ([peak-100k.md](peak-100k.md)) broke the containers' memory down:
   - `kubectl top` reports memory including the **file cache**, the operating system caching data files, which the
     kernel frees under pressure before it kills anything.
   - PostgreSQL's own memory was only ~36 MiB plus 140 MiB of shared buffers; the rest was cache. It was never close
     to being killed: a bigger limit only means more cache and fewer disk reads.
   - Kafka's own memory was ~410 MiB, 64 % of the old 640 MiB limit: real headroom, not an imminent failure.

   Neither container ever reached its limit (`memory.events`: max 0, oom_kill 0).
4. **Doubling the consumers gave ~21–27 %, not 2×.** Accepting and latency are unchanged. The processing side scales
   below linearly: each event is still its own database transaction, one commit each, on one PostgreSQL instance. The
   next gain is fewer transactions (processing events in batches), not more threads or pods. That is an inference from
   the scaling, not a measurement of PostgreSQL itself.
5. **Cold starts cost 3–5 s.** The first run after the backend restarts is 30–50 % slower (JIT warm-up). A deployment
   during a peak would be felt, a reason to keep rolling updates one pod at a time, as they are.

## Limits of this evidence

- **One machine.** Rancher Desktop's single-node k3s on 16 CPUs, with k6 in the same cluster competing for CPU. Absolute
  numbers will differ on real infrastructure; the comparisons were made under equal conditions.
- **Few runs.** Four warm runs per configuration, so the ~21 % is indicative, not precise. The ranges overlap in one
  run (after-5, 11.5 s).
- **CPU readings are coarse.** Kubernetes' metrics (`kubectl top`) average over 15–60 s windows, longer than these
  10-second bursts, so the CPU columns in `timeline.csv` are indicative only. Memory and the database samples are
  point-in-time and reliable.
- **Only real-time calls.** Batch calls were measured separately by the soak test
  ([soak-history.md](../../account-upgrade-e2e/test-results/soak-history.md)): 100,000 requests in 10 batches were
  accepted in 3.2 s.

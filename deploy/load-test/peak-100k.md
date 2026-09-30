# Peak load test: 100,000 real-time requests

The same test as the [10,000-request peak](README.md), at ten times the volume: 100,000 upgrade requests arriving at
once, each as its own real-time call.

- **Before:** the configuration after the 10,000-request test.
- **After:** one change, chosen from what the before runs showed.

Measured on 2026-09-30.

## Summary

| | Before | After | Change |
|---|---|---|---|
| Consumer offset commits (`spring.kafka.listener.ack-mode`) | per record | **per poll (batch)** | the only change |
| **Last request processed** (mean) | **66.0 s** | **47.9 s** | **−27 %** |
| Processing speed once the requests stop | ~1,970 events/s | ~3,045 events/s | **+55 %** |
| End to end, 100,000 requests | ~1,515 events/s | ~2,090 events/s | +38 % |
| Busiest Kafka broker (CPU) | ~1.54 cores | ~0.53 cores | −65 % |
| 100,000 requests accepted | 13.7–22.0 s | 14.5–23.7 s | unchanged (noise) |
| p95 latency | 55–91 ms | 57–100 ms | unchanged |
| Not accepted / lost / dead letters | 0 / 0 / 0 | 0 / 0 / 0 | |

- **Both configurations handle the peak without errors.** All 100,000 requests were accepted in 14–24 s, at
  4,200–7,300 requests/s, with 95 % answered within 100 ms. All were processed and notified, with 0 dead letters, in
  every run.
- **Processing is ~27 % faster after the change.**
- Nothing was scaled: still 2 backend pods, 8 partitions, 4 consumer threads per pod, 10 database connections per pod.

## What stayed the same

The configuration from the 10,000-request test (PR #25), in both before and after:

| Setting | Value |
|---|---|
| Backend pods | 2 |
| Topic partitions | 8 |
| Consumer threads per pod | 4 (8 consumers, one partition each) |
| Database connections per pod | 10 |
| Kafka / PostgreSQL memory limit | 1 GiB / 1 GiB |
| Load | k6 inside the cluster, 200 concurrent clients, 100,000 × `POST /api/realtime-upgrade`, 30 % eligible, a parent email on every other request |
| Rate limiter | off during the runs (a single load generator would only measure it), back on afterwards |

To run it: `REQUESTS=100000 TIMEOUT_SECONDS=1200 deploy/load-test/run.sh my-label`. See [README.md](README.md) for the
rate limiter and the warm-up run.

## Method

- **Warm-up first.** Before each series, one discarded 10,000-request run, because the first run after a restart is
  slower (JVM warm-up).
  - Before: 2 measured runs.
  - After: 3 measured runs. Two with the change as an environment override, then one on the build deployed from the
    committed code (Jenkins build 19), with the override removed.
- **What each run records** ([`run.sh`](run.sh)):
  - k6's throughput and latency;
  - every ~3 s: processed count, unsent emails, busy database connections, CPU and memory per pod;
  - exact seconds from the first request to the last accepted, last processed and last email sent (from the records'
    timestamps);
  - the eligible/ineligible split, and dead letters added.
- **"Processing speed once the requests stop"** is the slope of the processed count from 2 s after the last accepted
  request until the end. It separates processing from the phase where accepting and processing compete for the same
  CPU.

## Before: offsets committed per record

| Run | Accept 100,000 (s) | Requests/s | Median / p95 / p99 (ms) | Last processed (s) | Last email (s) | Processing after accept | Dead letters |
|---|---|---|---|---|---|---|---|
| 100k-before-1 | 22.0 | 4,537 | 38 / 91 / 132 | 69.6 | 70.7 | 1,942/s | 0 |
| 100k-before-2 | 13.7 | 7,285 | 23 / 55 / 87 | 62.3 | 62.6 | 1,991/s | 0 |

**Timeline (before-2)**, processed count every ~4 s:

```
0s 0 · 8s 3,990 · 17s 11,118 · 21s 18,688 · 29s 34,504 · 40s 58,019 · 52s 80,916 · 60s 96,749 · 64s 100,000
```

- **Processing crawls while requests arrive** (~650/s in the first 17 s), then runs at ~2,000/s. Accepting and
  processing share the backend pods' CPU: ~3–3.8 cores per pod at the peak.
- **One Kafka broker ran far hotter than the others:** kafka-1 at 1.54 cores, versus 0.6–0.8. It coordinates the
  consumer group, so every offset commit goes to it. With `ack-mode: record`, every processed event is followed by a
  synchronous commit. At ~2,000 events/s, that is ~2,000 extra round trips per second, all to that one broker, and
  each consumer thread waits for its commit before taking the next event.
- **Database connections were not the limit:** at most 9–10 busy of 20.
- **Memory** (`kubectl top`): PostgreSQL 625–696 MiB, Kafka up to 685 MiB, both under their 1 GiB limits. The
  configuration before PR #25 (512 / 640 MiB) would have read at or over its limits. See *Memory* below for what that
  number includes.

## The change

In `account-upgrade-backend/src/main/resources/application.yml`:

```yaml
    listener:
      ack-mode: batch    # was: record
```

The consumers now commit their offsets once per poll, after the poll's records are processed, instead of after every
record.

- **Why it's safe:** on a crash, up to one poll's records are delivered again, instead of at most one. The consumer
  already handles redelivery. `UpgradeRequestHandlerImpl` checks the event id, and writes the decision and its emails,
  in one transaction. A redelivered event is skipped, and no decision or email is written twice.
- **Tested:** the backend's 116 tests pass unchanged, including the tests for redelivery, concurrent processing and
  the dead-letter queue.

## After: offsets committed per poll

| Run | Accept 100,000 (s) | Requests/s | Median / p95 / p99 (ms) | Last processed (s) | Last email (s) | Processing after accept | Dead letters |
|---|---|---|---|---|---|---|---|
| 100k-after-1 (override) | 23.7 | 4,220 | 40 / 100 / 160 | 50.6 | 51.1 | 3,030/s | 0 |
| 100k-after-2 (override) | 14.5 | 6,909 | 25 / 57 / 77 | 43.0 | 43.0 | 3,060/s | 0 |
| 100k-after-3 (from code, build 19) | 23.2 | 4,303 | 40 / 96 / 147 | 50.1 | 50.2 | 3,044/s | 0 |

**Timeline (after-2)**, processed count every ~4 s:

```
0s 0 · 8s 7,835 · 17s 19,621 · 21s 31,034 · 29s 55,399 · 37s 79,695 · 41s 92,235 · 45s 100,000
```

- **Processing is faster in both phases:** ~1,150/s while requests arrive (was ~650/s), ~3,050/s after (was ~2,000/s).
- **The Kafka brokers are balanced:** 0.43–0.56 cores each. The coordinator's hot spot is gone.
- **PostgreSQL is now the busiest part:** 2.4–2.6 cores (was ~1.8). It is doing the work that remains, one
  transaction per event.
- **Database connections:** at most 9–10 busy of 20, as before.
- **The build from code matches the override:** run after-3 processed in 50.1 s, and the brokers were balanced
  (0.48–0.54 cores).

## Memory: what `kubectl top` measures

After the runs, PostgreSQL showed 977–1,017 MiB of its 1 GiB limit. Its memory broke down as follows (cgroup
`memory.stat`, after the after-2 run):

| Container | Own memory (anon) | Shared buffers (shmem) | File cache | Reached its limit / killed |
|---|---|---|---|---|
| PostgreSQL | 36 MiB | 140 MiB | 943 MiB | never / never |
| kafka-1 | 410 MiB | – | 323 MiB | never / never |

- **Most of the figure is file cache.** `kubectl top` reports memory including the file cache, the operating system
  caching the database and log files. The kernel frees that cache under pressure before it kills a container.
- **PostgreSQL uses ~180 MiB itself.** The rest is cache that grows with the database. The database was 650 MB after
  these runs.
- **Kafka's real use is ~410 MiB.** That was 64 % of the old 640 MiB limit.

This corrects the [10,000-request findings](README.md#findings), which read the same number as a risk of being
killed. The 1 GiB limits stay: they are safe, and more cache means fewer disk reads.

## Findings

1. **The system handles 100,000 requests at once without scaling out.** They are accepted in 14–24 s, the backlog
   waits in Kafka, and it is processed within ~50 s (~66 s before the change). Nothing is lost or dead-lettered.
2. **The bottleneck was offset commits, not the database or the consumer count.** Committing after every event cost
   a round trip per event, to a single broker. Committing per poll gave **27 % faster processing** and cut that
   broker's CPU by two-thirds, with no new pods, threads or connections.
3. **Database connections were never the limit.** At most 10 of 20 busy, at both 10,000 and 100,000 requests.
4. **Accepting and processing compete for the backend's CPU during the peak.** Processing runs ~2.7–3× faster once
   requests stop arriving. On a multi-node cluster, running the consumers in separate pods (the same image with the
   web API off) would give each its own CPU. On this single node they would still share it.
5. **PostgreSQL is now the most loaded component** (~2.5 cores): one transaction per event. The next gain is
   **batching the database writes** (several events per transaction), a code change, then a larger PostgreSQL. More
   connections or threads are not the next step (finding 3). Done since: [batch-writes.md](batch-writes.md).

## Limits of this evidence

- **One machine.** Rancher Desktop's single-node k3s on 16 CPUs, with k6 in the same cluster. Absolute numbers will
  differ on real infrastructure; before and after ran under the same conditions.
- **Few runs.** 2 before and 3 after. The before and after ranges do not overlap: 62.3–69.6 s versus 43.0–50.6 s.
- **Accepting times vary a lot** (14–24 s) in both configurations, with no pattern tied to the change.
- **CPU readings are coarse.** `kubectl top` averages over 15–60 s windows, so CPU figures are indicative; the
  counts, timings and memory breakdown are exact.
- **Disk.** The local volumes do not enforce their 2 GiB size. On real storage, each 100,000-request run adds ~100 MB
  to PostgreSQL (650 MB after all runs), and the outbox's delivered rows are purged after 7 days.

Raw data per run (configuration, k6 summary, timeline, result): `results/*100k*` (warm-ups: `results/*warmup-100k*`,
10,000 requests each).

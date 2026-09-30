# Batching the consumers' database writes: 100,000 requests before and after

The next step named by the [100,000-request test](peak-100k.md): the consumers wrote one PostgreSQL transaction per
event. They now write one per batch of events. This file measures that change the same way, and records a latent
email-relay problem the change exposed, and its fix.

Measured on 2026-09-30.

## Summary

| | Before: one transaction per event | After: one transaction per batch | Change |
|---|---|---|---|
| **Last request processed** (mean) | **59.7 s** | **20.6 s** | **−65 %** |
| Last request processed, after the last was accepted | 34–46 s later | **0–0.8 s later** | processing keeps up with arrivals |
| Processing speed while requests arrive | ~950–1,040 events/s | **~3,500–5,550 events/s** | **~4–5×** |
| **Last email sent** (mean) | **59.9 s** | **39.8 s** | **−34 %** |
| PostgreSQL CPU (peak) | 1.6–1.9 cores | 1.3–1.5 cores | less, for 3–5× the work per second |
| Busy database connections (peak) | 11 of 20 | 5–10 of 20 | |
| 100,000 requests accepted / p95 latency | 14–22 s / 55–87 ms | 15–26 s / 60–104 ms | unchanged (run-to-run noise) |
| Not accepted / lost / dead letters | 0 / 0 / 0 | 0 / 0 / 0 | |

- **Decisions:** batching made the consumers about 4–5× faster. All 100,000 decisions are made as fast as the requests
  arrive.
- **Emails:** the email outbox is now the last step, finishing ~15–20 s after the last decision.
- **The relay fix:** measuring the change exposed a query-plan problem in the email relay that could stall email
  delivery. It is fixed here too (*The email relay problem* below).
- **Unchanged:** configuration and scale. Still 2 backend pods, 8 partitions, 4 consumer threads per pod, and 10
  database connections per pod.

## The change

**Before.** Each event was its own transaction, with a round trip for each of:

- `SELECT EXISTS` (already processed?);
- an insert per email (1–2);
- the decision insert;
- the commit.

About 5 round trips per event.

**After.** A consumer receives a poll's records at once (`spring.kafka.consumer.max-poll-records`, 500) and writes
them in **one transaction with three statements** (`UpgradeRequestHandlerImpl.handleAll`):

1. `SELECT event_id ... WHERE event_id IN (...)`: which events are already stored;
2. one multi-row `INSERT INTO processed_upgrades ... ON CONFLICT DO NOTHING RETURNING event_id`: the new decisions, in
   poll order (the store order of each user), returning the ones actually stored;
3. one multi-row `INSERT INTO notification_outbox ...`: the emails of the decisions this transaction stored.

**The guarantees are unchanged:**

- **Idempotent.** An event already stored, or delivered twice within the batch, is skipped. A decision another
  instance stores at the same moment is not returned by the insert, so it gets no emails here.
- **All or nothing, per event.** If the batch fails for any reason, its transaction rolls back and the listener
  (`KafkaUpgradeRequestListener`) processes its events **one at a time** with the previous path. The first event that
  still fails is reported to Kafka's error handler as a `BatchListenerFailedException`, so that event alone is
  retried (1 s to 30 s backoff) and dead-lettered. The events before it are committed; those after it are delivered
  again.
- **Unreadable records:** reported the same way, after the readable records before them are processed. They go to the
  dead-letter queue without retries.
- **Order:** each user's events stay in order (one partition per user; rows inserted in poll order).
- **Offsets:** committed once per batch, as since [peak-100k](peak-100k.md).

**Tests.** 141 backend tests (116 before), including new ones for:

- the batch transaction (decisions and emails together; a failed email write rolls back the decisions);
- redelivered and in-batch duplicate events;
- two instances racing on one batch;
- per-user order;
- 8 threads processing the same batch;
- the listener's fallback and failure reporting;
- the multi-row inserts, including more than one statement's worth.

On the deployed build, the end-to-end suite (45) and the 65 corner cases pass. The corner cases cover ordering,
duplicates and mixed batches.

## Method

Same test as before ([README.md](README.md)): k6 in the cluster, 200 concurrent clients, 100,000 real-time requests,
30 % eligible, a parent email on every other request, rate limiter off during the runs.

- **Before:** 2 runs on the current `main` (build `3919d97`), measured today, after one discarded warm-up. The database
  had grown since the [peak-100k](peak-100k.md) runs (~0.8–0.9 million decisions), and these runs were slower than those
  (57–62 s against 43–51 s). So the comparison uses a fresh baseline, not the earlier numbers.
- **After:** 3 runs on the batching build deployed through Jenkins (build 23, `25fe9b6`), after one discarded
  warm-up.
- The rate limiter was switched back on afterwards, with no overrides left.

## Before: one transaction per event

| Run | Accept 100,000 (s) | Median / p95 / p99 (ms) | Processing while accepting | Last processed (s) | Last email (s) | Max unsent emails | Dead letters |
|---|---|---|---|---|---|---|---|
| 100k-per-event-tx-1 | 22.3 | 39 / 87 / 135 | 946/s | 57.5 | 57.5 | 1,799 | 0 |
| 100k-per-event-tx-2 | 14.4 | 25 / 55 / 76 | 1,036/s | 61.8 | 62.2 | 1,532 | 0 |

- **Processing lags far behind:** 34–46 s after the last request is accepted.
- **Emails keep pace with decisions:** at most ~1,800 unsent.
- **Resources:** PostgreSQL at 1.6–1.9 cores, 11 connections busy.

## The email relay problem, found on the way

The first run on the batching build (`100k-batched-tx-1`, before the relay fix):

- **Decisions were fast:** the last of 100,000 was made 25.6 s after the first request.
- **Emails stalled:** 97,760 unsent after one minute, and **57,280 still unsent after 9 minutes**, a drain rate of
  ~84 emails/s. The last email went out at **840.8 s**, only after the relay fix below was deployed (its
  `result.txt`).

**Cause.** The relay (`OutboxRelay`) claims the oldest due emails with `ORDER BY id LIMIT 20`.

- The table has a partial index for exactly this: `notification_outbox_pending (next_attempt_at, id) WHERE sent_at IS
  NULL`. But ordering by `id` alone cannot use it.
- While the backlog is small, PostgreSQL picks the partial index anyway. Batching creates ~150,000 unsent emails within
  seconds, the statistics then predict many matches, and the planner switches to walking the primary key from the
  start.
- That skips every **delivered** email first. Delivered rows are kept 7 days, and the test runs had left 1.3 million.

| `EXPLAIN ANALYZE` of one relay batch (20 rows), same data | Plan | Rows skipped | Time |
|---|---|---|---|
| `ORDER BY id` (before) | primary key scan | 1,345,570 | **601 ms** |
| `ORDER BY next_attempt_at, id` (after) | `notification_outbox_pending` | 0 | **2 ms** |

600 ms per 20 emails is ~33 emails/s per instance, which matches the ~84/s observed with two instances.

**Fix.** Order by `next_attempt_at, id`: the oldest due first. Ties keep insertion order, so a user's email still
precedes the parent's. A new relay test checks the order. After the fix was deployed, the remaining ~54,000 emails
drained before the deployment's tests had finished.

**This was not caused by batching:** any large email backlog, such as after an outage, with a week of delivered
emails in the table, would have triggered the same plan. Batching made it visible.

## After: one transaction per batch (with the relay fix)

| Run | Accept 100,000 (s) | Median / p95 / p99 (ms) | Processing while accepting | Last processed (s) | Last email (s) | Max unsent emails | Dead letters |
|---|---|---|---|---|---|---|---|
| 100k-batched-1 | 25.7 | 45 / 104 / 164 | 3,473/s | 26.7 | 45.2 | 81,100 | 0 |
| 100k-batched-2 | 17.5 | 30 / 72 / 99 | 4,806/s | 18.4 | 39.1 | 95,540 | 0 |
| 100k-batched-3 | 15.2 | 26 / 60 / 89 | 5,551/s | 16.8 | 35.1 | 88,820 | 0 |

- **Processing keeps up with arrivals:** the last request is processed within 0.8 s of being accepted.
- **Accepting is now the limit on decisions:** it is bounded by how fast requests arrive and are accepted (the backend's
  CPU is shared by both).
- **PostgreSQL does 3–5× the work per second with less CPU** (1.3–1.5 cores): three statements and one commit per
  ~500 events, instead of ~5 statements and a commit per event.
- **Emails are the last step:** up to ~95,000 wait at the peak, then drain at ~3,000–4,000/s. Two relays (one per pod)
  deliver 20 emails per transaction, with a separate update per email.

## Findings

1. **Batching was the biggest remaining gain.** Decisions: 59.7 s → 20.6 s for 100,000 requests. Processing speed
   while requests arrive: ~1,000 → ~3,500–5,550 events/s. PostgreSQL's CPU went down, not up.
2. **Processing is no longer behind the arrivals.** The backlog in Kafka no longer grows during the peak, and the
   last decision comes within a second of the last accepted request.
3. **It exposed, and this change fixes, an email relay query that could collapse to ~84 emails/s.** That could happen
   under any large email backlog once a week of delivered emails accumulates.
4. **Next bottleneck: the email relay.** It claims 20 emails per transaction and updates them one by one. Marking a
   batch sent with one `UPDATE ... WHERE id IN (...)`, and a larger batch size, would be the next step. The emails
   then finish 15–20 s after the last decision instead of in step with it. Done since, +80 %: [relay-throughput.md](relay-throughput.md).
5. **Retention matters.** The outbox keeps delivered rows 7 days. A heavy week means millions of rows. The fix makes
   the relay independent of that, but the retention period is worth sizing to the real traffic.

## Limits of this evidence

- **One machine.** Single-node k3s on 16 CPUs, with k6 in the same cluster. The before and after runs used the same
  conditions and the same warm-up. The after runs had the larger database (~1.0–1.3 million decisions, against
  ~0.8–0.9 million), which, if anything, favours the before runs.
- **Few runs.** 2 before and 3 after. The ranges don't overlap for decisions (57.5–61.8 s vs 16.8–26.7 s) or for
  emails (57.5–62.2 s vs 35.1–45.2 s).
- **Accepting times vary a lot** (14–26 s) in both configurations. That is independent of the change, but it shifts
  the end times.
- **CPU readings are coarse.** `kubectl top` averages over 15–60 s windows. The counts, timings, query plans and SQL
  timings are exact.

Raw data per run: `results/*100k-per-event-tx-*`, `results/*100k-batched-*`, and warm-ups `results/*warmup-batching-*`
(10,000 requests each).

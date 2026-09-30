# Email relay throughput: before and after

After [batching the consumers' writes](batch-writes.md), the email outbox was the last step to finish in a
100,000-request peak. This measures the relay on its own and one change to it.

Measured on 2026-09-30.

## Summary

| | Before | After | Change |
|---|---|---|---|
| Marking delivered emails sent | one `UPDATE` per email | **one `UPDATE ... WHERE id IN (...)` per batch** | the only change |
| 150,000 emails delivered in | 25.5 / 27.8 s | **12.8 / 17.4 s** | |
| **Throughput** (both relays) | 5,387–5,879 emails/s (mean 5,633) | **8,603–11,714 emails/s (mean 10,159)** | **+80 %** |
| Emails retried | 0 | 0 | |

Nothing else changed: 2 backend pods, one relay each, 20 emails per batch, polled every 500 ms, looping while batches
are full.

## Method

[`relay-bench.sh`](relay-bench.sh) measures the relays alone, with no HTTP, Kafka or eligibility involved:

1. It inserts 150,000 pending emails with one statement, about what a 100,000-request peak produces.
2. It samples the pending count and CPU every 2 s until all are delivered.
3. It times the delivery from the rows themselves: from their insert (`created_at`) to the last `sent_at`.
4. It deletes its rows afterwards.

```sh
EMAILS=150000 deploy/load-test/relay-bench.sh my-label       # bash, Git Bash or Linux, with kubectl access
```

Run while nothing else produces emails. Before: 2 runs on `main` (`d9dc305`). After: 2 runs on the build deployed
through Jenkins (build 27, `3503d32`).

## Before: one statement per delivered email

| Run | Delivered in | Throughput | Retried |
|---|---|---|---|
| relay-baseline-1 | 25.5 s | 5,879/s | 0 |
| relay-baseline-2 | 27.8 s | 5,387/s | 0 |

The relays were not short of CPU:

- backend pods under 0.5 core each;
- PostgreSQL about 1 core.

Each relay is one loop per pod, and every email cost a round trip, the `UPDATE` marking it sent. So the time went to
waiting on the database: ~0.36 ms per email per relay.

## The change

`OutboxRelay.relayBatch` delivers the claimed rows, then marks all the delivered ones sent with **one statement**.

- **Failed deliveries** (rare) are still recorded one by one, with their retry backoff.
- **After a crash, nothing changes:** the batch was already one transaction, so a crash before the commit un-marks
  the whole batch either way, and those emails are resent. That is the documented at-least-once behaviour; the channel
  receives an `eventId:role` key to drop duplicates.
- **Tests:** the relay tests (delivery, several batches per poll, retries and giving up, a failing row not blocking the
  others, two relays in parallel, due order) pass unchanged. So do the other 141 backend tests and the 44 end-to-end
  tests in the pipeline.

## After: one statement per batch

| Run | Delivered in | Throughput | Retried |
|---|---|---|---|
| relay-batch-update-20-1 | 12.8 s | 11,714/s | 0 |
| relay-batch-update-20-2 | 17.4 s | 8,603/s | 0 |

## Findings and next steps

1. **One statement per batch nearly doubles the relay** (+80 % mean). It removes one database round trip per email.
2. **The rest is mostly per-batch overhead and the simulated channel:** a `SELECT ... FOR UPDATE SKIP LOCKED`, the
   update and a commit per 20 emails, plus one log line per email. Larger batches (the setting
   `upgrade.notification.relay.batch-size`) would spread the per-batch cost, at the price of longer transactions
   holding locked rows.
3. **With a real email service, delivery time per email would dominate.** An SES or SMTP call takes milliseconds, not
   microseconds. Then the lever is sending in parallel (several relay workers per pod, or an asynchronous channel),
   not fewer database round trips.

## Limits

- **Few runs.** Two per side, with a large spread after the change (8,603–11,714/s). The ranges don't overlap.
- **One machine.** Single-node k3s on 16 CPUs.
- **Simulated delivery.** The channel only writes a log line (`EmailChannelImpl`).

Raw data: `results/*relay-baseline-*` and `results/*relay-batch-update-20-*`.

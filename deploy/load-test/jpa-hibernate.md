# JPA / Hibernate instead of JDBC: before and after

The backend's data access moved from Spring's `JdbcClient` to Spring Data JPA with Hibernate. This document checks
that the move costs nothing under peak load.

## Summary

| | JDBC (`3503d32`) | JPA / Hibernate (`3a9e83c`) |
|---|---|---|
| 100,000 requests: last decision | 20.8 / 18.6 s | 21.5 / 17.9 s |
| 100,000 requests: last email | 33.2 / 33.3 s | **32.0 / 24.7 s** |
| Accept latency, median / p95 / p99 | 34 / 82 / 116 ms, 27 / 71 / 110 ms | 34 / 87 / 124 ms, 30 / 68 / 90 ms |
| Max unsent emails | 81,033 / 85,760 | 75,889 / 59,220 |
| PostgreSQL peak CPU | 1.64 / 1.71 cores | 1.15 / 1.48 cores |
| Busy database connections (peak) | 7 / 11 | 5 / 6 |
| Relay alone, 150,000 emails | 8,603–11,714 emails/s (mean 10,159, [relay-throughput.md](relay-throughput.md)) | 8,905–10,147 emails/s (mean 9,689) |
| Dead letters, retried emails | 0 | 0 |

**Same throughput.** Decisions finish at the same time. Emails finish at the same time or sooner. PostgreSQL does
less work: the email inserts go out in batches of 50 rows (Hibernate JDBC batching, sent by the driver as multi-row
statements).

## What changed

- **Entities and repositories.** `ProcessedUpgradeEntity` and `OutboxEmailEntity` map the two tables. Hibernate
  validates them against the Liquibase schema at startup (`ddl-auto: validate`) and changes nothing.
- **Native SQL, only where JPA has no equivalent.** Decisions are stored with
  `INSERT ... ON CONFLICT DO NOTHING RETURNING event_id`: the insert must be duplicate-safe when two instances race
  on one event.
- **SKIP LOCKED.** The relay claims emails through a JPQL query with a pessimistic lock and SKIP LOCKED
  (`@Lock(PESSIMISTIC_WRITE)`, lock timeout -2).
- **Batched email inserts.** Hibernate batches inserts only when it assigns the ids itself. Changeset `004` replaces
  the outbox identity column with a sequence (`notification_outbox_id_seq`, increment 50): one `nextval` reserves 50
  ids. It is backward compatible: the JDBC build ran the runs above against the migrated schema. It was also verified
  with update, rollback and re-apply on a table with existing rows.
- **Single-event path.** It now stores the decision first, then writes the emails only if it stored the decision, as
  the batch path already did. A second email for the same event and role is now a unique-key error instead of a
  silent skip.

## Method

- **Where:** the Kubernetes deployment (Rancher Desktop k3s), 2 backend pods, 3 Kafka brokers, 8 partitions.
- **Load:** k6 in the cluster ([README.md](README.md)), 200 concurrent clients, 100,000 real-time requests,
  30 % eligible, a parent email on every other request. The rate limiter was off during the runs and back on
  afterwards.
- **Same database for both builds:** it had grown to ~1.8 million outbox rows. The JDBC build (`3503d32`, the relay
  batching of PR #28) was deployed onto the migrated schema for its runs, then the JPA build was restored.
- **Runs:** each build got one discarded warm-up run, then 2 measured runs.
- **Relay benchmark:** [`relay-bench.sh`](relay-bench.sh), 3 runs of 150,000 emails on the JPA build.

## Runs

| Run | Accept 100,000 (s) | Median / p95 / p99 (ms) | Last processed (s) | Last email (s) | Max unsent | Dead letters |
|---|---|---|---|---|---|---|
| 100k-jdbc-1 | 19.7 | 34 / 82 / 116 | 20.8 | 33.2 | 81,033 | 0 |
| 100k-jdbc-2 | 16.9 | 27 / 71 / 110 | 18.6 | 33.3 | 85,760 | 0 |
| 100k-jpa-1 | 20.6 | 34 / 87 / 124 | 21.5 | 32.0 | 75,889 | 0 |
| 100k-jpa-2 | 17.0 | 30 / 68 / 90 | 17.9 | 24.7 | 59,220 | 0 |

| Relay run (JPA) | Delivered in | Throughput | Retried |
|---|---|---|---|
| relay-jpa-1 | 14.8 s | 10,147/s | 0 |
| relay-jpa-2 | 15.0 s | 10,014/s | 0 |
| relay-jpa-3 | 16.8 s | 8,905/s | 0 |

Raw data (configuration, k6 summary, timeline, result) is in [`results/`](results), in the folders ending in
`-100k-jdbc-*`, `-100k-jpa-*` and `-relay-jpa-*`.

## Correctness

- **Backend build:** 143 tests pass, among them:
  - the Testcontainers integration tests, which apply the Liquibase changelog including `004`;
  - the naming-convention test.
- **Jenkins build 29:** deployed the JPA build, including the schema job with `004`, and the end-to-end suite (44)
  passed.
- **Corner cases:** all 65 pass against the deployed JPA build.

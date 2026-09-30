# Soak test history

Rows are appended by `SoakTest` (`account-upgrade-e2e`, `mvnw verify -Psoak`), one per run, against the Kubernetes deployment (2 backend pods, 3 Kafka brokers, PostgreSQL).

- **Rejected:** batch items refused at ingestion (e.g. Kafka unavailable), not lost data.
- **Lost:** accepted minus processed; this must always be 0.
- **Notified:** processed requests whose emails were all delivered when processing finished; the outbox keeps delivering after that.
- **Threads / heap:** per backend pod (`pod1/pod2`), from its actuator (`jvm.threads.live`, `jvm.memory.used` for the heap).

| Run at | Events sent | Accepted | Rejected | Processed | Lost | Notified | Dead letters | Seconds | Events/s | JVM threads before -> after | Heap used (MB) | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-30 12:32 | 4000 | 4000 | 0 | 4000 | 0 | 4000 | 0 | 26.5 | 151 | 67/72 -> 67/72 | 39/49 | verification run of the Java port (2 x 2,000) |

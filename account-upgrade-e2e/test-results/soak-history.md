# Soak test history

Rows are appended by `SoakTest` (`account-upgrade-e2e`, `mvnw verify -Psoak`), one per run, against the Kubernetes deployment (2 backend pods, 3 Kafka brokers, PostgreSQL).

- **Rejected:** batch items refused at ingestion (e.g. Kafka unavailable), not lost data.
- **Lost:** accepted minus processed; this must always be 0.
- **Notified:** processed requests whose emails were all delivered when processing finished; the outbox keeps delivering after that.
- **Threads / heap:** per backend pod (`pod1/pod2`), from its actuator (`jvm.threads.live`, `jvm.memory.used` for the heap).

| Run at | Events sent | Accepted | Rejected | Processed | Lost | Notified | Dead letters | Seconds | Events/s | JVM threads before -> after | Heap used (MB) | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-30 12:32 | 4000 | 4000 | 0 | 4000 | 0 | 4000 | 0 | 26.5 | 151 | 67/72 -> 67/72 | 39/49 | verification run of the Java port (2 x 2,000) |
| 2026-09-30 13:02 | 4000 | 4000 | 0 | 4000 | 0 | 4000 | 0 | 8.0 | 501 | 35/35 -> 35/35 | 38/45 | after publishing batches together (2 x 2,000) |
| 2026-09-30 13:04 | 100000 | 100000 | 0 | 100000 | 0 | 59179 | 0 | 103.5 | 966 | 35/35 -> 35/35 | 62/57 | default size, after publishing batches together |
| 2026-09-30 19:49 | 100000 | 100000 | 0 | 100000 | 0 | 21999 | 0 | 9.9 | 10098 | 43/43 -> 43/43 | 112/106 | JPA/Hibernate build (4e75bdf), default size |
| 2026-09-30 20:11 | 30000 | 30000 | 0 | 30000 | 0 | 8990 | 0 | 5.7 | 5268 | 43/43 -> 43/43 | 104/128 | traffic for the Grafana dashboard check |
| 2026-09-30 22:07 | 100000 | 100000 | 0 | 100000 | 0 | 13286 | 0 | 12.2 | 8205 | 43/41 -> 43/41 | 112/115 | with Loki and Splunk shipping logs |

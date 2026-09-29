# Soak test history

Rows are appended by `scripts/soak.ps1`, one per run. Findings and analysis are in [`TEST_REPORT.md`](../TEST_REPORT.md).

- **Rejected:** batch items refused because the queues were full (back-pressure), not lost data.
- **Lost:** accepted minus processed; this must always be 0.
- **Process memory:** OS working set of the Java process, not the live heap. Use `jcmd <pid> GC.class_histogram` for heap detail.

| Run at | Events sent | Accepted | Rejected | Processed | Lost | Dead letters | Seconds | Events/s | Threads before -> after | Process memory (MB) | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-28 18:36 | 100000 | 94145 | 5855 | 94145.0 | 0.0 | 0.0 | 18.1 | 5192 | 73 -> 74 | 380 | baseline after concurrency fixes |
| 2026-09-28 18:36 | 100000 | 89445 | 10555 | 89445.0 | 0.0 | 0.0 | 17.1 | 5245 | 73 -> 73 | 500 | repeat run, same app instance |

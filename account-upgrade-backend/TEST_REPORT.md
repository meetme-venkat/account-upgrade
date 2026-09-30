> **Historical record.** These results were measured against the earlier in-memory broker and store, which have since been removed; the service now runs on Kafka and PostgreSQL. The findings still explain several design choices.
>
> The live corner cases and the soak test now live in [`account-upgrade-e2e`](../account-upgrade-e2e/README.md#corner-cases-and-soak) as JUnit suites (`CornerCasesTest`, `SoakTest`) that run against the Kubernetes deployment; the PowerShell scripts below were removed. Their current results: [`corner-cases.md`](../account-upgrade-e2e/test-results/corner-cases.md), [`soak-history.md`](../account-upgrade-e2e/test-results/soak-history.md). The files in this folder's `test-results/` are the historical runs.

# Test Report: Corner Cases, Multi-threading, Concurrency and Memory

Test results for the account upgrade service. The service was tested against the running application on `localhost:8080`, using the default in-memory broker, and with the JUnit suite.

**Environment:** Windows 11, JDK 26.0.2 (project compiled with `--release 17`), Spring Boot 4.1.1, Maven 3.9.16 through `mvnw`. Test date: 2026-09-28.

### How these results are maintained

| File | Content | Updated by |
|---|---|---|
| `TEST_REPORT.md` (this file) | Findings, defects, fixes and analysis | By hand, when there is something new to explain |
| [`test-results/corner-cases.md`](test-results/corner-cases.md) | Result of all 65 live corner cases (historical) | Was rewritten on every run of the former `scripts/corner-cases.ps1` |
| [`test-results/soak-history.md`](test-results/soak-history.md) | One row per soak run: accepted, rejected, lost, dead letters, throughput, threads, memory (historical) | A row was appended on every run of the former `scripts/soak.ps1` |

The generated files hold the raw, always-current numbers. This report explains what they mean.

## Summary

| Suite | Result |
|---|---|
| JUnit unit and integration tests (`mvnw test`) | **62 / 62 pass** |
| Concurrency tests, repeated 5 times to check for flakiness | **5 / 5 runs green** (25 tests per run) |
| Live corner cases against the running app (`scripts/corner-cases.ps1`) | **65 / 65 pass** |
| Live soak, 100k events (`scripts/soak.ps1`), 3 runs | No slowdown within a run, 0 lost events, 0 dead letters, memory bounded |
| Live graceful shutdown under load | 16,794 queued events all processed before exit |

Testing found **10 defects**. All are fixed and verified:
- 1 found by the corner cases: result ordering (section 1.7);
- 9 found by the concurrency, idempotency and memory work (section 2).

---

## 1. Corner cases (live, against the running application)

Each case sends real HTTP requests. For cases processed asynchronously, the script waits until processing has finished and then checks the stored result through `GET /api/processed-upgrades` and `GET /api/notifications`.

### 1.1 Ingestion validation (18 cases)

| Case | Input | Expected | Observed |
|---|---|---|---|
| Missing `userId` | `{"userName":"A","age":20,"balance":50}` | 400 | 400, `userId: userId is required` |
| Blank `userId` | `"userId":"   "` | 400 | 400, `userId is required` |
| Invalid `parentEmail` | `"parentEmail":"not-an-email"` | 400 | 400, `parentEmail must be a valid email address` |
| Malformed JSON | `{"userId":` | 400 | 400, `Malformed request` |
| Empty body | *(none)* | 400 | 400, `Malformed request` |
| Age not a number | `"age":"abc"` | 400 | 400, `Malformed request` |
| Balance not a number | `"balance":"lots"` | 400 | 400, `Malformed request` |
| Array sent to the real-time endpoint | `[{...}]` | 400 | 400 |
| Object sent to the batch endpoint | `{...}` | 400 | 400 |
| Empty batch | `[]` | 400 | 400, `batch must contain at least one request` |
| Batch with an invalid 2nd item | `[{valid},{"userName":"no id"}]` | 400 naming the item | 400, `[1].userId: userId is required` |
| Batch over 10,000 items | 10,001 items | 400 | 400, `batch must not exceed 10000 requests` |
| Wrong content type | `text/plain` | 415 | 415 |
| Wrong method | `GET /api/realtime-upgrade` | 405 | 405 |
| Unknown path | `GET /api/does-not-exist` | 404 | 404 |
| Invalid status filter | `?status=MAYBE` | 400 | 400, `Invalid value 'MAYBE' for parameter 'status'` |
| Lower-case status filter | `?status=eligible` | 400 | 400 (enum values are case-sensitive) |
| Unknown JSON fields | `"favouriteColour":"blue"` | 202, field ignored | 202 |

Every error is returned as `application/problem+json` (RFC 9457).

### 1.2 Eligibility rules (24 cases)

Rules: `userName` must not be empty, 18 ≤ `age` ≤ 23, and `balance` ≥ 30. Every rule is checked, so a request can fail with several reasons.

| Case | Input | Status | Reasons recorded |
|---|---|---|---|
| Age 17 | age=17 | INELIGIBLE | `Age must be between 18 and 23 (inclusive) but was 17` |
| Age 18 (lower bound) | age=18 | ELIGIBLE | – |
| Age 23 (upper bound) | age=23 | ELIGIBLE | – |
| Age 24 | age=24 | INELIGIBLE | `... but was 24` |
| Age 0 | age=0 | INELIGIBLE | `... but was 0` |
| Negative age | age=-5 | INELIGIBLE | `... but was -5` |
| Age 200 | age=200 | INELIGIBLE | `... but was 200` |
| Age missing | *(no age)* | INELIGIBLE | `Age is required` |
| Decimal age | age=20.9 | ELIGIBLE | – *(see observation 1)* |
| Balance 29.99 | 29.99 | INELIGIBLE | `Balance must be at least $30 but was $29.99` |
| Balance 29.999 | 29.999 | INELIGIBLE | `... but was $29.999` (compared exactly as `BigDecimal`, no rounding) |
| Balance 30 (bound) | 30 | ELIGIBLE | – |
| Balance 30.00 | 30.00 | ELIGIBLE | – (scale does not matter) |
| Balance 0 | 0 | INELIGIBLE | `... but was $0` |
| Negative balance | -100 | INELIGIBLE | `... but was $-100` |
| Very large balance | 999999999999.99 | ELIGIBLE | – |
| Balance as a string | `"45.5"` | ELIGIBLE | – *(see observation 2)* |
| Balance missing | *(no balance)* | INELIGIBLE | `Balance is required` |
| Empty `userName` | `""` | INELIGIBLE | `User name must not be empty` |
| Blank `userName` | `"   "` | INELIGIBLE | `User name must not be empty` |
| Missing `userName` | *(no userName)* | INELIGIBLE | `User name must not be empty` |
| All three rules fail | `""`, age 99, balance 1 | INELIGIBLE | all 3 reasons, in rule order |
| Only `userId` sent | `{"userId":"..."}` | INELIGIBLE | 3 reasons (name, age required, balance required) |
| Non-English name | `"Zoë Łukasz 名"` | ELIGIBLE | – |

### 1.3 Notifications (6 cases)

| Case | Outcome | User emails | Parent emails |
|---|---|---|---|
| Eligible, `parentEmail` set | ELIGIBLE | 1 | **1** |
| Eligible, no `parentEmail` field | ELIGIBLE | 1 | 0 |
| Eligible, `parentEmail: null` | ELIGIBLE | 1 | 0 |
| Eligible, `parentEmail: ""` | ELIGIBLE | 1 | 0 *(see observation 3)* |
| Ineligible, `parentEmail` set | INELIGIBLE | 1 | **0** (the parent is only told about approvals) |
| Decline email content | INELIGIBLE | lists every reason, separated by `;` | – |

### 1.4 Duplicates, ordering and batches (7 cases)

| Case | Observed |
|---|---|
| Same user submitted twice, no `Idempotency-Key` | 2 different event IDs, both processed and stored (treated as two separate requests) |
| 20 events for one user in one batch (alternating eligible and ineligible) | Processed and returned in submission order |
| Batch with one eligible and one ineligible item | Receipts in input order; outcomes ELIGIBLE and INELIGIBLE |
| Requests rejected with 400 | Nothing stored or processed |

### 1.5 Load (6 cases)

| Case | Observed |
|---|---|
| Batch of 5,000 | 202 in ~103 ms |
| 300 concurrent real-time requests | 300 / 300 returned 202 |
| All 5,354 events from the run | Processed; the load drained ~0.83 s after the batch started |
| Load-batch outcomes | 1,500 ELIGIBLE, 3,500 INELIGIBLE, matching the generated data |
| Email outbox after the load | Holds 1,000 (capped) |

### 1.6 Query and health (5 cases)

| Case | Observed |
|---|---|
| `?status=ELIGIBLE` plus `?status=INELIGIBLE` | Together they return every record (1,826 + 3,528) |
| `?status=INELIGIBLE&userId=...` | 1 record |
| Unknown `userId` | 200 `[]` |
| Results for one user | Ordered by processing time |
| `/actuator/health` | `UP` |

### 1.7 Defect found by the corner cases

| Defect | Cause | Fix |
|---|---|---|
| `GET /api/processed-upgrades` could list one user's results out of order, even though they were processed in order (the consumer log showed `EIEIEI…`) | The results were sorted by `processedAt`. On Windows the clock only ticks about every 1.5 ms, so many records had the same timestamp, and ties came back in hash-map order | The repository now keeps insertion order. Regression test: `InMemoryProcessedUpgradeRepositoryTest` |

### 1.8 Behaviour worth reviewing (by design, not defects)

1. **Decimal age:** `"age": 20.9` is accepted and truncated to 20, because Jackson converts floats to integers by default. You may prefer to reject it with a 400.
2. **Numeric string:** `"balance": "45.5"` is accepted and converted.
3. **Empty `parentEmail`:** `"parentEmail": ""` passes validation and is treated as "no parent".
4. **Case-sensitive filter:** `?status=eligible` returns a 400. Only the exact values `ELIGIBLE` and `INELIGIBLE` are accepted.
5. **Empty `userName`:** this is an eligibility failure (the request is stored as INELIGIBLE and the user is told why), not a validation error.

---

## 2. Multi-threading, concurrency, idempotency and memory

### 2.1 Method

1. Reviewed every piece of code with shared state or thread interaction: the broker, the processor, the repository, the email outbox, the ingestion API and the lifecycle hooks.
2. Wrote tests that reproduce each suspected problem, and ran them against the **unfixed** code to confirm the defect.
3. Fixed each defect and re-ran the full suite. Ran the concurrency tests 5 more times to check for flakiness.
4. Validated the fixes against the running application: a soak test, a heap histogram after a full GC, thread counts, idempotency, oversized uploads, and a graceful shutdown under load.

### 2.2 Defects found and fixed

| # | Area | Defect | How it was shown | Fix |
|---|---|---|---|---|
| D1 | Memory and throughput | The email outbox was an unbounded `CopyOnWriteArrayList`. Every send copied the whole history while holding one lock shared by all consumer threads. Throughput fell as the history grew, the queues filled at ~55k events, and requests were rejected | Soak: 22,622 of 100k rejected. Heap: **160,112** `EmailMessage` objects retained | Ring buffer holding the last 1,000 (`upgrade.notification.outbox-capacity`), plus a total counter |
| D2 | Data loss | A `publish()` racing with `stop()` could return 202, but the event was never processed | `publishRacingWithStopNeverLosesAnAcceptedEvent` lost events in round 6 of 200 | Read/write lock: `publish` holds the read lock; `start`/`stop` flip `running` under the write lock. Consumers drain everything accepted |
| D3 | Fault isolation | An `Error` thrown by the handler killed the partition's only consumer thread without any log. The partition was never read again, so its users eventually got 503s | `handlerErrorDoesNotKillThePartitionConsumer` timed out | The consumer loop catches `Throwable` for each event, dead-letters the event and carries on |
| D4 | Ordering and thread leak | Calling `start()` twice created a second consumer per partition, which broke per-user ordering, and orphaned the first thread pool | `startingTwice…`: 2 concurrent consumers on 1 partition. `stopTerminatesAllConsumerThreads` found leaked threads | `start()` does nothing if already running; the check happens under the write lock |
| D5 | Idempotency | Two concurrent deliveries of the same event (possible with a Kafka rebalance) both passed the "already processed?" check, so the user was emailed repeatedly | `concurrentDuplicateDeliveries…`: **8** emails for 8 deliveries | The processor claims the event ID in a concurrent set before processing, and releases it in `finally` so a retry after a failure still works |
| D6 | Idempotency | A client that retried after a timeout had its request processed and emailed twice | Code review, then a live test | Optional `Idempotency-Key` header: the event ID is derived from the key (for a batch, the key plus the item index) |
| D7 | Memory | The dead-letter store was unbounded | `deadLetterStoreIsBounded`: 500 retained with a limit of 50 | Bounded deque (`upgrade.messaging.dead-letter-capacity`, default 1,000) plus a total counter |
| D8 | Memory | No request body limit. A huge JSON body, including a chunked upload with no `Content-Length`, was read fully into memory before the 10,000-item check ran | Code review, then a live test | `RequestBodySizeLimitFilter`: 5 MB (`upgrade.ingestion.max-request-size`). Oversized `Content-Length` is rejected up front; a chunked stream is cut off while reading. Both return `413` |
| D9 | Lifecycle | The broker started after the web server and stopped before it, so requests during startup or a graceful shutdown got 503s | Code review (lifecycle phases) | Broker phase set to `DEFAULT_PHASE - 4096`, below the web server's phases; `server.shutdown: graceful` |

### 2.3 Concurrency tests (JUnit)

| Test | Scenario | Before fix | After fix |
|---|---|---|---|
| `everyAcceptedEventIsDeliveredExactlyOnceUnderConcurrentPublishers` | 16 threads × 5,000 events, 8 partitions | Pass | Pass: 80,000 delivered, 0 duplicates |
| `preservesPerKeyOrderAndNeverProcessesOneKeyConcurrently` | 2,000 events for one user interleaved with 2,000 events for other users | Pass | Pass: exact order kept, 0 overlaps |
| `publishRacingWithStopNeverLosesAnAcceptedEvent` | Publisher thread racing `stop()`, 200 rounds | **Fail** (D2) | Pass |
| `handlerErrorDoesNotKillThePartitionConsumer` | Handler throws `AssertionError` | **Fail** (D3) | Pass: the next event is delivered; 1 dead letter |
| `startingTwiceDoesNotCreateDuplicateConsumers` | `start()` called twice, then 200 events for one user | **Fail** (D4): 2 concurrent consumers | Pass: at most 1 consumer at a time |
| `stopTerminatesAllConsumerThreads` | Count of `upgrade-consumer-*` threads after `stop()` | **Fail** (leak from D4) | Pass: 0 alive |
| `deadLetterStoreIsBounded` | 500 failures with capacity 50 | **Fail** (D7) | Pass: 50 retained, total 500 |
| `concurrentDuplicateDeliveriesNotifyAndStoreOnlyOnce` | 8 threads handle the same event at the same moment | **Fail** (D5): 8 emails | Pass: 1 record, 1 email |
| `failedProcessingReleasesTheEventSoARetryCanProcessIt` | 1st attempt throws, 2nd succeeds | Pass | Pass |
| `concurrentSavesOfSameEventIdStoreExactlyOne` | 8 threads save the same ID, 500 rounds | Pass | Pass: exactly 1 winner per round |
| `readsAreSafeWhileWritersAreActive` | 4 writers × 20,000 records with a concurrent reader | Pass | Pass: no `ConcurrentModificationException`; size never goes down |
| `outboxKeepsOnlyTheMostRecentMessagesButCountsAll` | 10,000 sends with capacity 100 | n/a (new) | Pass |
| `concurrentSendersNeverExceedCapacityOrLoseCount` | 8 threads × 5,000 sends with capacity 50 | n/a (new) | Pass: total 40,000; never over 50 |
| `retriedRequestsWithSameIdempotencyKeyAreProcessedAndNotifiedOnce` | Real-time and batch requests, each sent twice with a key | n/a (new) | Pass: same event ID, 1 record each |
| `oversizedBodyIsRejectedBeforeDeserialisation` | 6 MB body | n/a (new) | Pass: 413 |
| `brokerStartsBeforeAndStopsAfterTheWebServer` | Lifecycle phase ordering | n/a (new) | Pass |

**Flakiness check:** the concurrency and integration tests (25 tests) were run 5 more times, and all 5 runs passed.

### 2.4 Live validation (running application)

#### Soak test: 100,000 events (10 batches of 10,000, sent as fast as possible)

| Metric | Before the fixes | After the fixes |
|---|---|---|
| Throughput | Fell as the email history grew; queues filled at ~55k events | Steady **~13,000 events/s** end to end |
| Accepted and processed | 77,378 / 77,378 | 91,971 / 91,971 |
| Rejected (queue full) | 22,622 | 8,029 *(expected, see below)* |
| Dead letters | – | 0 |
| `EmailMessage` objects in the heap | **160,112** and growing | **1,000** (capped) |
| Heap used after a full GC | – | ~45 MB, almost all of it the processed-results store (91,971 `ProcessedUpgrade`) |
| Process threads before / after | 55 / 55 | 61 / 61 (no thread leak) |

**Throughput varies between runs.** The ~13,000 events/s run above used `-Xmx512m` with the shutdown endpoint enabled. Two later runs of the standard `java -jar` launch each measured about **5,200 events/s**. The cause of the difference has not been investigated. In every run, lost events, dead letters and the thread count were the same (0, 0 and stable). See [`test-results/soak-history.md`](test-results/soak-history.md) for every run.

The remaining rejections are **back-pressure working as designed**, not lost data. The test sent about 40,000 events/s, about 3× what the consumers can process. Once the 40,000-slot queues filled, the batch endpoint returned `REJECTED` receipts for the overflow instead of letting memory grow.

#### Idempotency (live)

| Scenario | Observed |
|---|---|
| Same request sent 3 times with `Idempotency-Key: pay-7781` | Same `eventId` all 3 times; **1** processed record; **1** parent email |
| Same request sent twice with no key | 2 event IDs, 2 records (without a key, a resend can't be told apart from a new request) |
| Invalid key (`bad key!`) | 400, `Idempotency-Key must be 1-128 characters of [A-Za-z0-9._:-]` |

#### Request size limit (live)

| Scenario | Observed |
|---|---|
| 6.5 MB body with `Content-Length` | **413**, rejected before the body is read |
| Same body sent chunked (no `Content-Length`) | **413**, stream cut off at 5 MB |
| App afterwards | Health `UP`; nothing from the oversized request stored |

#### Graceful shutdown under load (live)

1. Sent 30,000 events.
2. Triggered `POST /actuator/shutdown` while **16,794** events were still queued.
3. The process exited after ~0.7 s.

Results:
- **No events lost:** the log recorded 151,974 processed events, exactly the processed count before the test plus the 30,000 accepted.
- **Correct shutdown order:**
  1. `Commencing graceful shutdown. Waiting for active requests to complete`
  2. `Graceful shutdown complete` (web server)
  3. `In-memory broker stopped` (after draining)
- No `undelivered events` warning was logged.
- A request sent after shutdown was refused at the connection level. It did not get a misleading 202.

(The actuator shutdown endpoint was enabled for this test only, through command-line flags. It is not enabled in `application.yml`.)

### 2.5 Verified correct from the start (no change needed)

- Thread-safe multi-producer publishing to the bounded partition queues.
- One consumer thread per partition, so a user's events are never processed in parallel and stay in order.
- `saveIfAbsent` is atomic (`ConcurrentHashMap.putIfAbsent`).
- Repository reads are safe during concurrent writes.
- The eligibility rules and the notification service are stateless and immutable after construction, so they are safe to share between threads.
- A failed event is retried on its own partition thread, so per-user order survives retries.

### 2.6 Known limitations (documented, not fixed)

| Limitation | Impact | Recommended approach |
|---|---|---|
| The in-memory idempotency claim covers one JVM | Across several instances or a restart, duplicate protection depends on the store | PostgreSQL with `event_id` as the primary key, using `INSERT ... ON CONFLICT DO NOTHING` |
| Reusing an `Idempotency-Key` with a different payload is not detected | The second request is silently treated as a duplicate | Store a hash of the payload with each key and return `422` on a mismatch |
| Notifications are sent at least once | A crash after sending but before saving means a redelivery emails again | Transactional outbox |
| The processed-results store is unbounded | Grows by design, because it stands in for the database | A real database with a retention policy |
| `GET /api/processed-upgrades` has no pagination | Response size grows with the store | Add `page` and `size` parameters |
| Consumer throughput | Synchronous console logging (4 INFO lines per event) dominates processing time | Async appender, or DEBUG level for the per-event log lines |
| Kafka profile | Not tested against a live broker (no Docker available) | Run `docker compose up -d` with the `kafka` profile |

---

## 3. How to reproduce

```powershell
# Unit, integration and concurrency tests
.\mvnw.cmd test

# Start the app
.\mvnw.cmd -DskipTests package
java -jar target\account-upgrade-service-1.0.0.jar

# The live corner cases and the soak test (formerly scripts\corner-cases.ps1 and scripts\soak.ps1) are now JUnit
# suites in account-upgrade-e2e, run against the Kubernetes deployment:
#   cd ..\account-upgrade-e2e; .\mvnw.cmd verify -Pcorner-cases
#   cd ..\account-upgrade-e2e; .\mvnw.cmd verify -Psoak "-De2e.soak.note=after changing X"

# Heap check after a full GC (replace <pid> with the app's process ID)
jcmd <pid> GC.run
jcmd <pid> GC.class_histogram | Select-String mercur
```

Useful metrics while testing are at `/actuator/metrics/{name}`:
- `upgrade.processed`
- `upgrade.broker.pending`
- `upgrade.broker.dead-letters`
- `upgrade.notifications.sent`

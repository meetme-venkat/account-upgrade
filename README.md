# Account Upgrade Service

An event-driven Spring Boot service that ingests account upgrade requests from two channels: batch and real-time. It checks each request against
the eligibility rules, notifies the user (and the parent, where relevant), stores the outcome and serves it back over REST.

- **Stack:** Java 17+, Spring Boot 4.1, Kafka (Spring Kafka), JUnit 5, Mockito, AssertJ, Awaitility
- **No external infrastructure is needed.** The default profile replaces Kafka with an in-memory partitioned broker and stores data in memory.

## Architecture

```
POST /api/batch-upgrade ──┐
                          ├─► IngestionService ──► EventPublisher ──► topic "upgrade-requests"
POST /api/realtime-upgrade┘   (validate, 202)        (key = userId)       │
                                                                          ▼
                                            UpgradeRequestProcessor  (consumer, 1 thread / partition)
                                              1. idempotency check (eventId)
                                              2. EligibilityService ──► [UserNameRule, AgeRangeRule, MinimumBalanceRule]
                                              3. NotificationService ──► EmailSender (mock: log + in-memory list)
                                              4. ProcessedUpgradeRepository (in-memory)
                                                                          │
GET /api/processed-upgrades  ◄────────────────────────────────────────────┘
GET /api/notifications       (simulated emails, for inspection)
```

Each package has a single responsibility and could be split into its own microservice. Packages talk to each other only through interfaces or the topic.

| Package | Responsibility |
|---|---|
| `ingestion` | REST ingestion API; turns requests into `UpgradeRequestedEvent`s and publishes them |
| `messaging` | Topic abstraction (`EventPublisher`, `UpgradeRequestHandler`) with two adapters: `inmemory` (default) and `kafka` (profile `kafka`) |
| `processing` | Consumer that orchestrates eligibility → notification → persistence |
| `eligibility` | Pluggable rules engine; thresholds configurable in `application.yml` |
| `notification` | Builds the user and parent emails; `EmailSender` port with a mock adapter |
| `persistence` | `ProcessedUpgradeRepository` port with a thread-safe in-memory adapter |
| `query` | Read API for processed requests |
| `web` | Global error handling (RFC 9457 problem details) |

### Event processing guarantees

- **Asynchronous:** the ingestion endpoints return `202 Accepted` with an `eventId` as soon as the event is queued. Eligibility is decided by the consumer threads.
- **Partitioning and ordering:** events are partitioned by `userId`. Events for the same user are processed in order, and different partitions are processed in parallel. The partition count is set by `upgrade.messaging.partitions`.
- **Back-pressure:** in-memory partitions are bounded (`queue-capacity`). When a partition is full:
  - a real-time request gets `503`;
  - a batch item gets a `REJECTED` receipt, and the rest of the batch continues.
- **Retries and dead letters:** a failed delivery is retried `max-attempts` times with `retry-backoff`, then parked:
  - in-memory broker: in its dead-letter store;
  - Kafka: on the `upgrade-requests-dlt` topic.
- **Idempotency:** delivery is at-least-once, so the processor skips any `eventId` it has already stored. It also claims each `eventId` while processing it, so two concurrent deliveries of the same event (for example after a Kafka rebalance) notify the user only once.
- **Client retries:** send an `Idempotency-Key` header (1–128 characters of `A-Z a-z 0-9 . _ : -`). The event ID is then derived from the key (for a batch, from the key plus the item's index), so a retried request produces the same event IDs and is processed and notified once. Without a key, every call creates new events.
- **Notification failures:** these do not trigger reprocessing, which would send duplicate emails to the recipients that succeeded. Instead they are recorded as `notificationSent: false`.
- **Graceful shutdown:** the broker starts before the web server and stops after it. On shutdown, in-flight HTTP requests finish first, then consumers drain every queued event. An event accepted with `202` is never dropped by a normal shutdown.
- **Fault isolation:** an unexpected `Error` thrown while processing an event dead-letters that event but does not kill its partition's consumer thread.

### Memory bounds

| Structure | Bound | Config key |
|---|---|---|
| Partition queues | `partitions × queue-capacity` events (default 40,000) | `upgrade.messaging.queue-capacity` |
| Dead-letter store | Most recent 1,000; the total count is kept separately | `upgrade.messaging.dead-letter-capacity` |
| Mock email outbox (`GET /api/notifications`) | Most recent 1,000; the total count is kept separately | `upgrade.notification.outbox-capacity` |
| Request body | 5 MB, enforced for both `Content-Length` and chunked uploads (`413` beyond) | `upgrade.ingestion.max-request-size` |
| Processed-request store | Unbounded: it stands in for the database | – |

Metrics are exposed at `/actuator/metrics/{name}`:
- `upgrade.processed`
- `upgrade.broker.pending`
- `upgrade.broker.dead-letters`
- `upgrade.notifications.sent`

## Eligibility rules

All rules are evaluated, and every failure reason is recorded.

| Rule | Condition | Config key |
|---|---|---|
| `UserNameRule` | `userName` not null or blank | – |
| `AgeRangeRule` | 18 ≤ `age` ≤ 23 | `upgrade.eligibility.min-age` / `max-age` |
| `MinimumBalanceRule` | `balance` ≥ 30 | `upgrade.eligibility.min-balance` |

To add a rule, create another `@Component` that implements `EligibilityRule`. It is picked up automatically.

**Validation versus eligibility:** only structural problems are rejected at ingestion with `400`: a missing `userId`, a malformed `parentEmail`, invalid JSON or an empty batch. Business-rule failures, such as an empty `userName`, go through processing. They are stored as `INELIGIBLE` and the user is notified.

## Notifications

| Outcome | User | Parent |
|---|---|---|
| ELIGIBLE | approval email | approval email if `parentEmail` is present |
| INELIGIBLE | decline email listing the reasons | – |

Emails are logged as `[EMAIL] to USER u100 | subject | body` and can be viewed at `GET /api/notifications`. The request schema has no user email address, so the user recipient is identified by `userId`.

## Build, test, run

You need JDK 17 or later. Maven is optional, because the wrapper downloads it.

```bash
# Windows: use mvnw.cmd instead of ./mvnw
./mvnw test                       # run the unit and integration tests
./mvnw spring-boot:run            # or: ./mvnw package && java -jar target/account-upgrade-service-1.0.0.jar
```

The app listens on `http://localhost:8080` (health check: `/actuator/health`).

### Running against real Kafka (optional)

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka      # KAFKA_BOOTSTRAP_SERVERS defaults to localhost:9092
```

## API

### `POST /api/batch-upgrade`: batch ingestion

```bash
curl -X POST http://localhost:8080/api/batch-upgrade -H "Content-Type: application/json" -d '[
  {"userId":"u100","userName":"Alice","age":19,"balance":120.50,"parentEmail":"alice.parent@example.com"},
  {"userId":"u101","userName":"Bob","age":23,"balance":30,"parentEmail":null},
  {"userId":"u102","userName":"","age":17,"balance":12.75,"parentEmail":"carl.parent@example.com"}
]'
```

Response `202 Accepted`:

```json
{
  "total": 3, "accepted": 3, "rejected": 0,
  "receipts": [
    {"userId":"u100","eventId":"a60cce96-...","source":"BATCH","status":"ACCEPTED"},
    {"userId":"u101","eventId":"15084f8e-...","source":"BATCH","status":"ACCEPTED"},
    {"userId":"u102","eventId":"9a60b8d1-...","source":"BATCH","status":"ACCEPTED"}
  ]
}
```

### `POST /api/realtime-upgrade`: real-time ingestion

```bash
curl -X POST http://localhost:8080/api/realtime-upgrade -H "Content-Type: application/json" \
  -d '{"userId":"u200","userName":"Dana","age":25,"balance":29.99}'
```

Response `202 Accepted`:

```json
{"userId":"u200","eventId":"139df25f-...","source":"REALTIME","status":"ACCEPTED"}
```

### `GET /api/processed-upgrades`: processed requests

Optional filters: `?status=ELIGIBLE|INELIGIBLE` and `?userId=...`

```json
[
  {"eventId":"a60cce96-...","userId":"u100","source":"BATCH","status":"ELIGIBLE","reasons":[],
   "notificationSent":true,"processedAt":"2026-09-28T12:27:08.229Z"},
  {"eventId":"9a60b8d1-...","userId":"u102","source":"BATCH","status":"INELIGIBLE",
   "reasons":["User name must not be empty",
              "Age must be between 18 and 23 (inclusive) but was 17",
              "Balance must be at least $30 but was $12.75"],
   "notificationSent":true,"processedAt":"2026-09-28T12:27:08.229Z"},
  {"eventId":"139df25f-...","userId":"u200","source":"REALTIME","status":"INELIGIBLE",
   "reasons":["Age must be between 18 and 23 (inclusive) but was 25",
              "Balance must be at least $30 but was $29.99"],
   "notificationSent":true,"processedAt":"2026-09-28T12:27:08.246Z"}
]
```

### `GET /api/notifications`: simulated emails

### Errors

Errors are returned as `application/problem+json`:

```json
{"status":400,"title":"Validation failed","detail":"Request validation failed",
 "errors":["userId: userId is required","parentEmail: parentEmail must be a valid email address"]}
```

Batch element errors carry the item's index, for example `"[1].userId: userId is required"`. If the broker cannot accept a request, the response is `503 Service Unavailable`.

More sample requests are in [`requests.http`](requests.http), which you can run from IntelliJ or VS Code REST Client.

## Tests

| Test class | Covers |
|---|---|
| `EligibilityRulesTest` | Each rule, including boundaries (17/18/23/24, 29.99/30) and null or blank input |
| `EligibilityServiceTest` | Mockito rules: all pass; all failures are collected with no short-circuit; the three real rules together |
| `UpgradeRequestProcessorTest` | Mockito: eligible and ineligible outcomes are persisted, notification failure is recorded, duplicate events are skipped |
| `NotificationServiceTest` | Mockito: user only, user and parent, decline with reasons, partial delivery failure |
| `IngestionServiceTest` | Mockito: event creation, real-time failure propagation, partial batch failure |
| `InMemoryMessageBrokerTest` | Per-key ordering, retry then dead-letter, retry recovery, back-pressure, stopped broker |
| `InMemoryMessageBrokerConcurrencyTest` | 16 concurrent publishers deliver exactly once; no concurrent processing of one key; publish racing stop loses nothing (200 rounds); an `Error` doesn't kill the consumer; double `start()`; no thread leaks; bounded dead letters |
| `UpgradeRequestProcessorConcurrencyTest` | 8 concurrent deliveries of one event produce 1 record and 1 email; the claim is released after a failure so a retry can proceed |
| `RepositoryConcurrencyTest` | Atomic `saveIfAbsent` under contention; reads during concurrent writes |
| `InMemoryProcessedUpgradeRepositoryTest` | Store order is kept even when timestamps are equal; duplicate event IDs are rejected |
| `InMemoryEmailSenderTest` | Outbox capacity under concurrent senders; total count is exact |
| `UpgradeFlowIntegrationTest` | Full HTTP flow through the async pipeline, validation errors, `Idempotency-Key` retries, `413` for oversized bodies, lifecycle phase ordering |

The full results are in [`TEST_REPORT.md`](TEST_REPORT.md): 65 live corner cases, the concurrency and memory findings, the soak test and the graceful-shutdown test. The live test scripts are in `scripts/`.

## Production notes

- **Persistence:** swap `InMemoryProcessedUpgradeRepository` for a PostgreSQL adapter, with `event_id` as the primary key so `saveIfAbsent` becomes `INSERT ... ON CONFLICT DO NOTHING`. This is also what makes idempotency hold across instances and restarts; the in-memory claim only covers one JVM.
- **Idempotency keys:** a key reused with a different payload is not detected, and the second request is silently de-duplicated. A production API would store a hash of the payload with each key and answer a mismatch with `422`.
- **Query size:** `GET /api/processed-upgrades` returns every record. Add pagination before the store grows large.
- **Reliable decision events:** use a transactional outbox so each decision is saved and published in a single transaction.
- **Email:** replace `InMemoryEmailSender` with an SMTP or SES adapter.
- **Batch ingestion:** very large loads would move to Spring Batch reading from files or object storage.

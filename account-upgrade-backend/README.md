# Account Upgrade Service

An event-driven Spring Boot service that ingests account upgrade requests from two channels: batch and real-time. It checks each request against
the eligibility rules, notifies the user (and the parent, where relevant), stores the outcome and serves it back over REST.

- **Stack:** Java 17+, Spring Boot 4.1, Kafka (Spring Kafka), PostgreSQL (JDBC + Flyway), JUnit 5, Mockito, AssertJ, Awaitility
- **Infrastructure:** PostgreSQL and Kafka, always as containers: `spring-boot:run` starts them (Docker Compose support), tests use Testcontainers, and deployments point at managed services (e.g. RDS, MSK).

## Architecture

```
POST /api/batch-upgrade ──┐
                          ├─► IngestionService ──► EventPublisherImpl ──► topic "upgrade-requests"
POST /api/realtime-upgrade┘   (validate, 202)        (key = userId, acks=all)     │
                                                                                   ▼
                          UpgradeRequestHandlerImpl  (Kafka consumer group, N threads per instance)
                            ┌─ one PostgreSQL transaction ───────────────────────────────────────┐
                            │ 1. idempotency check (eventId)                                     │
                            │ 2. EligibilityService ──► EligibilityRule impls (eligibility.impl) │
                            │ 3. NotificationService ──► EmailSenderImpl (notification_outbox)   │
                            │ 4. ProcessedUpgradeRepositoryImpl (processed_upgrades)             │
                            └────────────────────────────────────────────────────────────────────┘
                                                                                   │
                          OutboxRelay (every instance, FOR UPDATE SKIP LOCKED) ──► EmailChannel (logs)
                                                                                   │
GET /api/processed-upgrades  ◄─────────────────────────────────────────────────────┘
GET /api/notifications       (delivered emails, for inspection)
```

Each package has a single responsibility and could be split into its own microservice. Packages talk to each other only through interfaces or the topic.

| Package | Responsibility |
|---|---|
| `ingestion` | REST ingestion API; turns requests into `UpgradeRequestedEvent`s and publishes them |
| `messaging` | Topic ports (`EventPublisher`, `UpgradeRequestHandler`) and the Kafka adapter: publisher, listener, topics, retry and dead-letter policy |
| `processing` | Consumer that orchestrates eligibility → notification → persistence in one transaction |
| `eligibility` | Pluggable rules engine; thresholds configurable in `application.yml` |
| `notification` | Builds the user and parent emails. The transactional outbox (`EmailSenderImpl`), the `OutboxRelay` that delivers them, and the `EmailChannel` port (log-only today) |
| `persistence` | `ProcessedUpgradeRepository` port and its PostgreSQL adapter (Flyway schema in `db/migration`) |
| `query` | Read API for processed requests |
| `guardrails`, `web` | Production guardrails, CORS, global error handling (RFC 9457 problem details) |

### Code conventions

- **Ports and implementations:** modules talk through interfaces (`ProcessedUpgradeRepository`, `EventPublisher`, `EmailSender`, ...). A class implementing one of the application's own interfaces is named `<Name>Impl` and lives in an `impl` package of its module, for example `persistence.impl.ProcessedUpgradeRepositoryImpl` or `messaging.kafka.impl.EventPublisherImpl`. The only implementation of an interface is named after it (`EmailSender` → `EmailSenderImpl`); several implementations of one interface keep a descriptive name (`AgeRangeRuleImpl`, `UserNameRuleImpl`). Other modules depend on the interface, never on an `impl` package.
- **Framework types keep framework names:** classes implementing only Spring, Kafka or servlet interfaces (`CorsConfig`, `ProductionReadinessCheck`, the filters) are not `Impl` classes.
- **Enforced:** `ImplementationNamingConventionTest` fails the build on a misnamed or misplaced implementation, and on anything else in an `impl` package. Test doubles are exempt.

### Event processing guarantees

- **Asynchronous:** the ingestion endpoints return `202 Accepted` with an `eventId` once Kafka has acknowledged the event (`acks=all`, idempotent producer). Eligibility is decided by the consumers.
- **Partitioning and ordering:** events are keyed by `userId`. Events for the same user are processed in order, and different partitions in parallel, across all instances in the consumer group. The partition count is `upgrade.messaging.partitions`, and threads per instance is `upgrade.messaging.kafka.consumer-concurrency`.
- **Broker unavailable:** the producer gives up after 3 s (`max.block.ms`) or 9 s (`delivery.timeout.ms`), always before the 10 s publish wait ends. So:
  - a real-time request gets `503`, meaning "not written";
  - a batch item gets a `REJECTED` receipt, and the rest of the batch continues.
- **Retries and dead letters:** a failed record is retried with exponential backoff (1 s up to 30 s, about 3.5 minutes in total, so events survive a short database outage). After that it goes to `upgrade-requests-dlt`. Unreadable payloads go there immediately.
- **Idempotency and exactly-once effects:** delivery is at-least-once. For each event, the processor writes the duplicate check, the decision and the email rows in **one PostgreSQL transaction**:
  - a crash or database error rolls everything back, so the redelivered event is processed cleanly;
  - a concurrent duplicate on another instance (after a rebalance) finds the decision already taken (`ON CONFLICT DO NOTHING`) and discards its own email rows;
  - on one instance, an in-process claim also stops two threads from processing the same event.
- **Client retries:** send an `Idempotency-Key` header (1–128 characters of `A-Z a-z 0-9 . _ : -`). The event ID is then derived from the key (for a batch, from the key plus the item's index), so a retried request produces the same event IDs and is processed and notified once. Without a key, every call creates new events.
- **Email delivery:** `OutboxRelay` runs on every instance and claims rows with `FOR UPDATE SKIP LOCKED`, so no row is taken twice. It retries failures with backoff (1 s up to 5 min, 8 attempts). `notificationSent` turns `true` once every email for the decision has been delivered, usually within a second. Delivery is at-least-once: a crash between sending and marking a row sent means one resend, which the channel can drop using the `eventId:role` key.
- **Graceful shutdown:** in-flight HTTP requests (and their publishes) finish, and the Kafka consumers stop after finishing their current record. Anything accepted is already durable in Kafka, so nothing needs draining.

### Resource bounds

| Resource | Bound | Config key |
|---|---|---|
| Request body | 5 MB, enforced for both `Content-Length` and chunked uploads (`413` beyond) | `upgrade.ingestion.max-request-size` |
| Batch size | 10,000 items (`400` beyond) | – |
| `GET /api/processed-upgrades` | Newest `limit` records (default 1000, max 5000) | – |
| `GET /api/notifications` | Newest 1,000 delivered emails | `upgrade.notification.outbox-capacity` |
| Database connections | 10 per instance, 3 s connection timeout, 5 s lock and 10 s statement timeouts | `spring.datasource.hikari.*` |
| Outbox | Delivered rows are deleted after 7 days; undelivered rows are kept | `upgrade.notification.relay.retention` |

Metrics are exposed at `/actuator/metrics/{name}`:
- `upgrade.processed`
- `upgrade.dead-letters`
- `upgrade.notifications.sent`, `upgrade.notifications.pending`, `upgrade.notifications.failed`
- Kafka client metrics, for example consumer lag: `kafka.consumer.fetch.manager.records.lag.max`

## Eligibility rules

All rules are evaluated, and every failure reason is recorded.

| Rule | Condition | Config key |
|---|---|---|
| `UserNameRuleImpl` | `userName` not null or blank | – |
| `AgeRangeRuleImpl` | 18 ≤ `age` ≤ 23 | `upgrade.eligibility.min-age` / `max-age` |
| `MinimumBalanceRuleImpl` | `balance` ≥ 30 | `upgrade.eligibility.min-balance` |

To add a rule, create another `@Component` that implements `EligibilityRule`, named `<Name>RuleImpl` in `eligibility.impl`. It is picked up automatically.

**Validation versus eligibility:** only structural problems are rejected at ingestion with `400`: a missing `userId`, a malformed `parentEmail`, invalid JSON or an empty batch. Business-rule failures, such as an empty `userName`, go through processing. They are stored as `INELIGIBLE` and the user is notified.

## Notifications

| Outcome | User | Parent |
|---|---|---|
| ELIGIBLE | approval email | approval email if `parentEmail` is present |
| INELIGIBLE | decline email listing the reasons | – |

Delivered emails are logged as `[EMAIL] to USER u100 | subject | body (key <eventId>:USER)` and can be viewed at `GET /api/notifications`. The request schema has no user email address, so the user recipient is identified by `userId`.

## Build, test, run

You need JDK 17 or later. Maven is optional, because the wrapper downloads it.

You need JDK 17+ and a **Docker engine**: Docker Desktop, or Rancher Desktop with the `dockerd (moby)` engine. PostgreSQL and Kafka always run as containers; nothing is installed locally.

```bash
# Windows PowerShell: use .\mvnw.cmd instead of ./mvnw (cmd.exe: mvnw.cmd)
./mvnw spring-boot:run            # starts PostgreSQL + Kafka from docker-compose.yml, then the app
./mvnw test                       # unit + integration tests (Testcontainers starts PostgreSQL and Kafka)
./mvnw verify                     # tests + coverage gate + Enforcer rules
```

- **`spring-boot:run` and Docker Compose:** it uses Spring Boot's Docker Compose support. It runs `docker compose up` for [`docker-compose.yml`](docker-compose.yml), reads the containers' ports and credentials, and connects to them, so no connection settings are needed. The containers stop when the app stops. PostgreSQL gets a random host port, so it never clashes with anything already on the machine.
- **Endpoint:** the app listens on `http://localhost:8080` (health check: `/actuator/health`).
- **Integration tests:** they start throwaway `postgres:16` and `apache/kafka` containers, once per test run, shared by all test classes. The prod-profile test runs against a **3-broker Kafka cluster**, because the prod guardrails require replication factor 3. CI runs the same tests with the runner's Docker.

### Running as a container

```bash
docker build -t account-upgrade-backend .
```

The image runs the `prod` profile, which applies the [production guardrails](PRODUCTION_GUARDRAILS.md):
- it refuses to start with Kafka topics that can't survive a broker failure (needs replication factor 3 and `min.insync.replicas` 2);
- it enables rate limiting, strict input handling and a locked-down actuator.

[`../docker-compose.yml`](../docker-compose.yml) runs the image against PostgreSQL and a 3-broker Kafka cluster.

| Environment variable | Purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (image default) for the production guardrails |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL connection (required; no default) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers, comma-separated (required; no default) |
| `SPRING_KAFKA_SECURITY_PROTOCOL` | `SSL` for Amazon MSK with TLS (default `PLAINTEXT`) |
| `UPGRADE_MESSAGING_PARTITIONS` | Topic partitions (default 4). Must be at least replicas × consumer concurrency, and can only grow later |
| `UPGRADE_MESSAGING_KAFKA_CONSUMER_CONCURRENCY` | Consumer threads per instance (default 4) |
| `UPGRADE_MESSAGING_KAFKA_REPLICATION_FACTOR` / `_MIN_INSYNC_REPLICAS` | Topic durability (defaults 1 / 1 for a single dev broker). `prod` requires 3 / 2 |
| `UPGRADE_GUARDRAILS_RATE_LIMIT_RPS` / `_BURST` / `_ENABLED` | Per-client rate limit on `/api/**` (defaults 20 / 40 / on) |
| `UPGRADE_WEB_CORS_ALLOWED_ORIGINS` | Comma-separated browser origins allowed to call `/api/**` directly. Leave it empty (the default) when the frontend reaches the API through its reverse proxy |

The UI lives in [`../account-upgrade-frontend`](../account-upgrade-frontend) and is deployed separately. The only link between the two is this REST API.

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

Optional filters: `?status=ELIGIBLE|INELIGIBLE` and `?userId=...`. Results are in processing order, capped at the newest `?limit=` records (default 1000, maximum 5000).

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

### `GET /api/notifications`: delivered (simulated) emails

The newest 1,000 emails delivered by the outbox relay, oldest first.

### Errors

Errors are returned as `application/problem+json`:

```json
{"status":400,"title":"Validation failed","detail":"Request validation failed",
 "errors":["userId: userId is required","parentEmail: parentEmail must be a valid email address"]}
```

Batch element errors carry the item's index, for example `"[1].userId: userId is required"`. If the broker cannot accept a request, the response is `503 Service Unavailable`.

More sample requests are in [`requests.http`](requests.http), which you can run from IntelliJ or VS Code REST Client.

## Tests

*(IT)* marks integration tests: they start the application against PostgreSQL and Kafka containers (Testcontainers).

| Test class | Covers |
|---|---|
| `EligibilityRulesTest` | Each rule, including boundaries (17/18/23/24, 29.99/30) and null or blank input |
| `EligibilityServiceTest` | Mockito rules: all pass; all failures are collected with no short-circuit; the three real rules together |
| `UpgradeRequestHandlerImplTest` | Mockito: eligible and ineligible outcomes are persisted, notification failure is recorded, duplicate events are skipped |
| `NotificationServiceTest` | Mockito: user only, user and parent, decline with reasons, partial delivery failure |
| `IngestionServiceTest` | Mockito: event creation, real-time failure propagation, partial batch failure |
| `RateLimitFilterTest` / `ProductionReadinessCheckTest` / `KafkaConfigTest` | Rate limiting; CORS, Kafka RF/minISR and transaction-manager startup rules; topic and DLT durability settings |
| `ImplementationNamingConventionTest` | Every implementation of an application interface is a `<Name>Impl` in an `impl` package, and `impl` packages hold nothing else (see [Code conventions](#code-conventions)) |
| `UpgradeFlowIntegrationTest` *(IT)* | HTTP → Kafka → decision + outbox → relay → query, with `notificationSent` turning true after delivery; validation errors; `Idempotency-Key` retries; `413` for oversized bodies; the query limit |
| `TransactionalProcessingTest` *(IT)* | **Two instances racing on one event commit one decision and one email per recipient**; a failed decision save rolls back the emails and the retry starts clean; redeliveries are skipped |
| `UpgradeRequestHandlerImplConcurrencyTest` *(IT)* | 8 concurrent deliveries on one instance produce 1 record and 1 email; the claim is released after a failure so a retry can proceed |
| `ProcessedUpgradeRepositoryImplTest` *(IT)* | Store once (`ON CONFLICT`), field round trip, store order with equal timestamps, filtered and limited `find`, `notificationSent` derived from the outbox |
| `OutboxRelayTest` *(IT)* | Delivers and marks rows, drains several batches, backoff then gives up at max attempts, a failing row doesn't block others, **two relays in parallel deliver each row exactly once**, retention purges only delivered rows |
| `ProdProfileIntegrationTest` *(IT, 3 brokers)* | The prod profile end to end: startup guardrails, security headers, request IDs, rate limiting, strict input, locked-down actuator |
| `CorsConfigTest` *(IT)* | CORS off by default; configured origins get a preflight answer |

[`TEST_REPORT.md`](TEST_REPORT.md) is a historical record: its live corner cases, soak and shutdown tests were run against the earlier in-memory broker, which has since been removed.

## Production notes

- **Idempotency keys:** a key reused with a different payload is not detected, and the second request is silently de-duplicated. A production API would store a hash of the payload with each key and answer a mismatch with `422`.
- **Query paging:** `GET /api/processed-upgrades` is capped by `limit`. Browsing further back needs cursor pagination (`?before=<seq>`).
- **Email:** implement `EmailChannel` with an SES or SMTP adapter. `EmailChannelImpl` only logs.
- **Kafka publish circuit breaker:** when Kafka is down, each ingestion request still waits up to the producer's 3 s `max.block.ms`. A breaker would fail fast.
- **Batch ingestion:** very large loads would move to Spring Batch reading from files or object storage.

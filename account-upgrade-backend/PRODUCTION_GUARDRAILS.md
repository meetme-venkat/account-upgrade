# Production guardrails: account-upgrade-backend

These are the protections this service enforces before and while it runs in production. Each one lives in code, configuration or CI, not only in this document. The **Enforced by** column says where.

Activate them with `SPRING_PROFILES_ACTIVE=prod`. The Docker image sets `prod` by default.

## 1. Fail-fast startup checks

`ProductionReadinessCheck` (`guardrails/`) runs only in the `prod` profile. Startup **aborts** with a list of every violation found.

| Check | Result | Fix |
|---|---|---|
| Wildcard CORS origin (`*`) | Startup fails | List exact origins in `UPGRADE_WEB_CORS_ALLOWED_ORIGINS` |
| Kafka replication factor < 3, `min.insync.replicas` < 2, or minISR ≥ RF | Startup fails | Set `UPGRADE_MESSAGING_KAFKA_REPLICATION_FACTOR=3` and `_MIN_INSYNC_REPLICAS=2`. Topics then survive a broker failure with every acknowledged write on 2 brokers |
| The transaction manager isn't the JDBC one | Startup fails | Happens if something else (for example a Kafka transaction manager from `spring.kafka.producer.transaction-id-prefix`) replaces it. The decision and outbox writes would silently stop sharing a transaction |
| Rate limit disabled | Warning logged | Re-enable it, or make sure a gateway rate-limits `/api` |

## 2. Runtime protections

| Guardrail | Behaviour | Enforced by | Config |
|---|---|---|---|
| Per-client rate limit on `/api/**` | Token bucket per client IP. Beyond it: `429` problem+json with `Retry-After`. The bucket map is size-bounded | `RateLimitFilter` | `UPGRADE_GUARDRAILS_RATE_LIMIT_ENABLED` (on in prod), `_RPS` (20), `_BURST` (40) |
| Real client IP behind the proxy | `X-Forwarded-For` / `-Proto` are trusted from internal addresses only | `server.forward-headers-strategy: native` | – |
| Request body cap | `413` above 5 MB, including chunked bodies | `RequestBodySizeLimitFilter` | `upgrade.ingestion.max-request-size` |
| Batch size cap | `400` above 10,000 items | `IngestionController` | – |
| Field length limits | `userId` ≤ 64, `userName` ≤ 100, `parentEmail` ≤ 254 characters, else `400` | `UpgradeRequest` bean validation | – |
| Unknown JSON fields rejected | `400 Unknown field 'x'` instead of silently dropping a client typo | `spring.jackson.deserialization.fail-on-unknown-properties` (prod) | – |
| Broker unavailable | The producer gives up within 3–9 s: `503` for real-time requests, `REJECTED` receipts for batch items. Nothing is half-written | `EventPublisherImpl`, producer timeouts | – |
| Idempotent retries | `Idempotency-Key` gives deterministic event ids, so an event is processed and notified once | `IngestionService`, `UpgradeRequestHandlerImpl` | – |
| **No email without a decision, and no decision without its emails** | Each decision and its outbox rows are written in one transaction. A crash, a DB error or two instances racing on one event roll back cleanly (`ON CONFLICT DO NOTHING` on both tables) | `UpgradeRequestHandlerImpl`, `EmailSenderImpl` | – |
| Email delivery retries | The relay claims rows with `FOR UPDATE SKIP LOCKED` (no row is claimed twice across instances). Failures back off from 1 s to 5 min, up to 8 attempts, then count as failed. At-least-once, with an `eventId:role` idempotency key | `OutboxRelay` | `upgrade.notification.relay.*` |
| Outbox retention | Delivered rows are deleted after 7 days (they contain email addresses). Undelivered rows are kept | `OutboxRelay.purgeDelivered` | `upgrade.notification.relay.retention` |
| Durable topics | Both `upgrade-requests` and `upgrade-requests-dlt` are created with the configured RF and `min.insync.replicas`. **Kafka never changes the RF of an existing topic**: fix older topics by hand or with infrastructure-as-code | `KafkaConfig` | `upgrade.messaging.kafka.*` |
| Survives short DB outages | Failed records retry with exponential backoff (1 s to 30 s, about 3.5 min in total) before the DLT. Unreadable payloads go to the DLT immediately | `KafkaConfig` error handler | – |
| Bounded producer blocking | `max.block.ms` 3 s, `delivery.timeout.ms` 9 s, both below the 10 s publish wait. A `503` means the event was not written | `application.yml` | – |
| Database timeouts | Pool connection timeout 3 s, `lock_timeout` 5 s, `statement_timeout` 10 s. The DB is not part of readiness, so an RDS blip doesn't pull every instance out of the load balancer | `application.yml` | – |
| Bounded query | `GET /api/processed-upgrades` returns at most the newest `limit` records (default 1000, max 5000), filtered in SQL | `ProcessedUpgradeController` | – |
| Graceful shutdown | In-flight HTTP requests and their publishes finish; consumers stop after their current record. Accepted events are already durable in Kafka | `server.shutdown: graceful` | `spring.lifecycle.timeout-per-shutdown-phase` |

## 3. Security hardening

| Guardrail | Enforced by |
|---|---|
| `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on every response, including errors and `429` responses | `SecurityHeadersFilter` |
| CORS off by default. When enabled: exact origins, `GET`/`POST` only, only the `Content-Type` and `Idempotency-Key` headers | `CorsConfig` |
| No stack traces, exception names or binding details in error responses | `server.error.*` (prod), `GlobalExceptionHandler` |
| Actuator limited to `health`, `info` and `metrics`. Health details hidden. No `env`, `beans`, `heapdump` and so on | `management.*` (prod) |
| Container runs as a non-root user. Heap is bounded (`MaxRAMPercentage=75`) and the JVM exits on OOM so the orchestrator restarts it | `Dockerfile` |

## 4. Observability

| Guardrail | Enforced by |
|---|---|
| A request id on every request: the caller's `X-Request-Id` if well formed (`[A-Za-z0-9._-]{1,64}`), otherwise a new UUID. It is echoed in the response and included in every log line as `[id]`. The frontend proxy sends its nginx `$request_id`, so both services log the same id | `RequestIdFilter`, `logging.pattern.correlation` |
| Liveness and readiness probes: `/actuator/health/liveness`, `/actuator/health/readiness` | `management.endpoint.health.probes` |
| Metrics: `upgrade.processed`; `upgrade.dead-letters` and `upgrade.notifications.sent` (per instance: sum across instances); `upgrade.notifications.pending` and `upgrade.notifications.failed` (whole table: aggregate with max, not sum); Kafka consumer lag (`kafka.consumer.fetch.manager.records.lag.max`) | `MetricsConfig`, `KafkaConfig`, `OutboxRelay` |

## 5. Build and delivery gates

| Gate | Fails the build when | Enforced by |
|---|---|---|
| Tests | Any of the unit or integration tests fails | `mvn verify`, CI |
| Coverage | Line or branch coverage drops below 85% (currently about 89% and 87%) | JaCoCo `check` in `pom.xml` (`coverage.*.minimum`) |
| Toolchain | Java < 17 or Maven < 3.9 | Maven Enforcer |
| Dependencies | Duplicate declarations or unconverged transitive versions. SNAPSHOT dependencies in a release build | Maven Enforcer |
| Code structure | A class implementing an application interface isn't named `<Name>Impl` or isn't in an `impl` package of its module, or an `impl` package holds anything else (framework-only implementations such as filters and `@Configuration` classes are exempt) | `ImplementationNamingConventionTest` |
| Supported JDKs | The build breaks on Java 17 or on Java 21 | `.github/workflows/backend.yml` matrix |
| Image behaviour | Against the compose stack (PostgreSQL + 3 Kafka brokers), the prod image doesn't become ready, a request doesn't flow through to a delivered notification, or RF 1 is accepted | `backend.yml` `image` job |
| Dependency freshness | – (weekly update PRs for Maven, the base image and Actions) | `.github/dependabot.yml` |

Run all the gates locally with:

```bash
./mvnw verify          # coverage report: target/site/jacoco/index.html
```

## Pre-deployment checklist

- [ ] `SPRING_PROFILES_ACTIVE` includes `prod`, with `SPRING_DATASOURCE_*` and `SPRING_KAFKA_BOOTSTRAP_SERVERS` set. On MSK with TLS, also set `SPRING_KAFKA_SECURITY_PROTOCOL=SSL`.
- [ ] Topic settings are chosen for peak load: `UPGRADE_MESSAGING_PARTITIONS` is at least the maximum replicas × `UPGRADE_MESSAGING_KAFKA_CONSUMER_CONCURRENCY` (partitions can only grow), with RF 3 and minISR 2.
- [ ] RDS `max_connections` ≥ replicas × Hikari pool size (10), plus headroom.
- [ ] RDS storage is encrypted: the outbox holds email addresses and decision reasons.
- [ ] Alerts exist on `upgrade.notifications.failed` > 0 and on sustained `upgrade.notifications.pending`.
- [ ] `UPGRADE_WEB_CORS_ALLOWED_ORIGINS` is empty (the frontend proxies `/api`) or lists exact HTTPS origins.
- [ ] The rate limit fits the expected traffic. It is per instance, so N replicas allow N times the rate. Clients behind one NAT share a bucket.
- [ ] Liveness and readiness probes point at `/actuator/health/liveness` and `/readiness`.
- [ ] TLS terminates at the ingress or load balancer, which sets `X-Forwarded-Proto`.
- [ ] Alerts exist on `upgrade.dead-letters` growth and on sustained consumer lag.

## Known limits

- Rate-limit state is per instance and in memory. Use a gateway or a shared store (such as Redis) for a global limit.
- Email delivery is at least once: a crash between delivering and marking a row sent means one resend.
- When Kafka is down, each ingestion request still waits up to 3 s (`max.block.ms`). A circuit breaker would fail fast.
- No authentication. Put the API behind an authenticating gateway, or add Spring Security (OAuth2 resource server) before exposing it publicly.

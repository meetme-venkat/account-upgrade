-- Processed upgrade requests. event_id is the idempotency key: INSERT ... ON CONFLICT DO NOTHING
-- makes "store once" atomic across instances. seq preserves store order (per user, processing order);
-- timestamps alone cannot, since several records can share one.
CREATE TABLE processed_upgrades (
    seq          BIGINT GENERATED ALWAYS AS IDENTITY,
    event_id     VARCHAR(64)  PRIMARY KEY,
    user_id      VARCHAR(64)  NOT NULL,
    source       VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    reasons      TEXT         NOT NULL,  -- JSON array of strings
    processed_at TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX processed_upgrades_seq ON processed_upgrades (seq);
CREATE INDEX processed_upgrades_user ON processed_upgrades (user_id, seq);
CREATE INDEX processed_upgrades_status ON processed_upgrades (status, seq);

-- Transactional outbox: notification rows are written in the same transaction as the decision and
-- delivered afterwards by OutboxRelay. No foreign key: within that transaction the outbox rows are
-- inserted before the decision row.
CREATE TABLE notification_outbox (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    recipient       VARCHAR(254) NOT NULL,
    subject         TEXT         NOT NULL,
    body            TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      TEXT,
    CONSTRAINT notification_outbox_event_role UNIQUE (event_id, role)
);

-- Only undelivered rows are polled, so the index stays small as sent rows accumulate.
CREATE INDEX notification_outbox_pending ON notification_outbox (next_attempt_at, id) WHERE sent_at IS NULL;
CREATE INDEX notification_outbox_sent ON notification_outbox (sent_at) WHERE sent_at IS NOT NULL;

--liquibase formatted sql

-- Transactional outbox: notification rows are written in the same transaction as the decision and
-- delivered afterwards by OutboxRelay. No foreign key: within that transaction the outbox rows are
-- inserted before the decision row.
--
-- Precondition: databases created by the backend's former Flyway migration V1 already have this table.
-- It is then recorded as applied (MARK_RAN) instead of failing, which adopts them into Liquibase.

--changeset account-upgrade:002-notification-outbox
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'notification_outbox'
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
--rollback DROP TABLE notification_outbox;

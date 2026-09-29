--liquibase formatted sql

-- Processed upgrade requests. event_id is the idempotency key: INSERT ... ON CONFLICT DO NOTHING
-- makes "store once" atomic across instances. seq preserves store order (per user, processing order);
-- timestamps alone cannot, since several records can share one.
--
-- Precondition: databases created by the backend's former Flyway migration V1 already have this table.
-- It is then recorded as applied (MARK_RAN) instead of failing, which adopts them into Liquibase.

--changeset account-upgrade:001-processed-upgrades
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'processed_upgrades'
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
--rollback DROP TABLE processed_upgrades;

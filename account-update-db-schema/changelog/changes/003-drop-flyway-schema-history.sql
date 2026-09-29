--liquibase formatted sql

-- Before this service existed, the backend created the schema with Flyway. Changesets 001 and 002 adopted those
-- databases into Liquibase, so Flyway's history table is obsolete and is dropped here. Databases created by
-- Liquibase never had it: the precondition then records this changeset as applied (MARK_RAN).
--
-- No rollback: the table only held Flyway's own bookkeeping, which nothing reads any more.

--changeset account-upgrade:003-drop-flyway-schema-history
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'flyway_schema_history'
DROP TABLE flyway_schema_history;
--rollback empty

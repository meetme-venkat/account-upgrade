--liquibase formatted sql

-- The backend writes outbox rows with JPA/Hibernate, which batches inserts only when it assigns the ids itself, from
-- a sequence, in blocks: one nextval per 50 rows instead of per row (pooled allocation, allocationSize 50 on the
-- entity). So the identity column becomes a plain column whose default is a sequence, as with bigserial:
--   - a standalone sequence: PostgreSQL hides identity sequences from information_schema.sequences, where Hibernate's
--     schema validation looks for it;
--   - INCREMENT BY 50: each nextval reserves the block of 50 ids ending at the returned value;
--   - it starts 50 above the highest existing id, so the first block is above every existing row.
--
-- Backward compatible (expand): inserts without an id, as the previous backend makes them, still take the next value
-- from the sequence through the column default. Such a value is never inside a block reserved by another nextval, so
-- ids cannot collide while both versions run. The changeset runs in one transaction, and DROP IDENTITY locks the table,
-- so no insert happens between dropping the identity and setting the default.

--changeset account-upgrade:004-outbox-id-allocated-in-blocks
ALTER TABLE notification_outbox ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE notification_outbox_id_seq AS BIGINT INCREMENT BY 50 OWNED BY notification_outbox.id;
SELECT setval('notification_outbox_id_seq', COALESCE(MAX(id), 0) + 50, false) FROM notification_outbox;
ALTER TABLE notification_outbox ALTER COLUMN id SET DEFAULT nextval('notification_outbox_id_seq');
--rollback ALTER TABLE notification_outbox ALTER COLUMN id DROP DEFAULT;
--rollback DROP SEQUENCE notification_outbox_id_seq;
--rollback ALTER TABLE notification_outbox ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY;
--rollback SELECT setval(pg_get_serial_sequence('notification_outbox', 'id'), COALESCE(MAX(id), 0) + 1, false) FROM notification_outbox;

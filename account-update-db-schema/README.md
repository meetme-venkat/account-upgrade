# account-update-db-schema

The database schema service. It owns the PostgreSQL schema of the Account Upgrade platform and migrates it with
[Liquibase](https://docs.liquibase.com/). It is a **job**, not a server: it runs `liquibase update`, brings the schema
up to date, and exits. Exit code 0 means the schema is current.

It is **deployed first**. The backend starts only after this job completed successfully, and the frontend only
once the backend is healthy (see the root [`docker-compose.yml`](../docker-compose.yml)). If a migration fails, nothing
after it starts and the deployment rolls back. The backend never changes the schema itself.

```
account-update-db-schema  ──exit 0──►  account-upgrade-backend  ──healthy──►  account-upgrade-frontend
     (Liquibase update)                    (uses the schema)
```

| | |
|---|---|
| Image | `liquibase/liquibase:5.0.4-alpine` + the PostgreSQL JDBC driver (pinned, checksum-verified) + the changelog |
| Runs as | the image's non-root `liquibase` user; the changelog is read-only |
| Changelog | [`changelog/db.changelog-master.yaml`](changelog/db.changelog-master.yaml), including [`changelog/changes/`](changelog/changes) |
| History | tables `databasechangelog` (applied changesets, with checksums) and `databasechangeloglock` |

## Schema

| Changeset | Creates |
|---|---|
| [`001-processed-upgrades`](changelog/changes/001-processed-upgrades.sql) | `processed_upgrades`: one row per processed request (`event_id` is the idempotency key, `seq` the store order) and its indexes |
| [`002-notification-outbox`](changelog/changes/002-notification-outbox.sql) | `notification_outbox`: the transactional email outbox and its partial indexes for pending and sent rows |

## Run it

It needs the connection settings, as Liquibase environment variables:

| Variable | Example |
|---|---|
| `LIQUIBASE_COMMAND_URL` | `jdbc:postgresql://postgres:5432/account_upgrade` |
| `LIQUIBASE_COMMAND_USERNAME` | `postgres` |
| `LIQUIBASE_COMMAND_PASSWORD` | (secret) |

```bash
docker build -t account-update-db-schema .
docker run --rm --network <db network> \
  -e LIQUIBASE_COMMAND_URL=jdbc:postgresql://postgres:5432/account_upgrade \
  -e LIQUIBASE_COMMAND_USERNAME=postgres -e LIQUIBASE_COMMAND_PASSWORD=postgres \
  account-update-db-schema                 # update (the default command)
```

Any other Liquibase command works the same way. Put it after the image name:

| Command | Does |
|---|---|
| `status --verbose` | Lists changesets not applied yet |
| `history` | Lists applied changesets |
| `update-sql` | Prints the SQL `update` would run, without running it (for review before production) |
| `rollback-count --count=1` | Rolls back the most recent changeset (see *Rollback* below) |
| `validate` | Checks the changelog, including that applied changesets were not edited |

Where it runs:
- `docker compose up` in the repository root: before the backend, on every deployment. When nothing is new it finds
  nothing to do and exits in a few seconds.
- `./mvnw spring-boot:run` in the backend: the backend's development `docker-compose.yml` runs it after PostgreSQL.
- The backend's integration tests apply this changelog to their test database, so they test against exactly this schema.

## Changing the schema

1. Add a new file in `changelog/changes/`, numbered after the last one (`003-...sql`), starting with
   `--liquibase formatted sql` and one or more `--changeset account-upgrade:<id>` blocks.
2. Give every changeset a `--rollback` statement.
3. Include the file at the end of `db.changelog-master.yaml`.
4. **Never edit or reorder a changeset that has been applied anywhere.** Liquibase stores a checksum of each one and
   refuses to run if an applied changeset changed. Fix mistakes with a new changeset.
5. Keep changes **backward compatible** with the backend version currently running (expand, then contract): the
   schema is migrated before the new backend starts, and a backend rollback does not roll the schema back. For
   example, add a nullable column first and make it required in a later release, once no running version writes
   without it.

CI ([`.github/workflows/db-schema.yml`](../.github/workflows/db-schema.yml)) checks every change: the changelog applies to
an empty database, a second run changes nothing, every changeset rolls back and applies again, and a database created by
the former Flyway migration is adopted with its data intact. The backend's CI also runs, because its tests use this
changelog.

## Rollback

Deployments only move the schema forward. When a release fails, the pipeline restores the previous backend and
frontend images and re-runs the previous schema image, which finds nothing to do. So the database keeps the newer
schema, and the previous backend must still work with it (hence rule 5 above).

To undo a schema change by hand, run `rollback-count --count=<n>` with the image of the release that introduced it
(older images don't have the changeset's rollback). Check `update-sql`/`history` first, and back up the database:
rolling back a `CREATE TABLE` drops the table and its data.

## Adopting existing databases

Before this service existed, the backend created the schema with Flyway (`V1__processed_upgrades_and_outbox.sql`).
Each changeset has a precondition: if its table already exists, it is recorded as applied (`MARK_RAN`) instead of
failing. A database created by Flyway is therefore adopted on the first run, with its data untouched. The old
`flyway_schema_history` table is left in place and no longer used.

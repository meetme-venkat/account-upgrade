# syntax=docker/dockerfile:1
# Database schema service: applies the Liquibase changelog to PostgreSQL, then exits (0 = schema up to date).
# Deployed first; the backend starts only after it completed successfully.
#   docker build -t account-update-db-schema .
#   docker run --rm \
#     -e LIQUIBASE_COMMAND_URL=jdbc:postgresql://db:5432/account_upgrade \
#     -e LIQUIBASE_COMMAND_USERNAME=postgres -e LIQUIBASE_COMMAND_PASSWORD=... \
#     account-update-db-schema                        # default command: update
# Other Liquibase commands work the same way, e.g. `... account-update-db-schema status --verbose`.

FROM liquibase/liquibase:5.0.4-alpine

# Liquibase 5 ships without JDBC drivers. Same PostgreSQL driver as the backend, pinned and verified.
ARG POSTGRESQL_JDBC_VERSION=42.7.13
ADD --chmod=644 \
    --checksum=sha256:6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93 \
    https://repo1.maven.org/maven2/org/postgresql/postgresql/${POSTGRESQL_JDBC_VERSION}/postgresql-${POSTGRESQL_JDBC_VERSION}.jar \
    /liquibase/lib/

# Read-only for the (non-root) liquibase user.
COPY --chown=root:root --chmod=755 changelog /liquibase/changelog

# Connection settings (LIQUIBASE_COMMAND_URL / _USERNAME / _PASSWORD) are supplied at runtime.
ENV LIQUIBASE_COMMAND_CHANGELOG_FILE=db.changelog-master.yaml \
    LIQUIBASE_SEARCH_PATH=/liquibase/changelog \
    LIQUIBASE_SHOW_BANNER=false \
    LIQUIBASE_ANALYTICS_ENABLED=false

USER liquibase
CMD ["update"]

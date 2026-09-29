package com.mercur.upgrade;

import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

/**
 * Creates the schema in a test database the way production does: by running the Liquibase changelog of the
 * {@code account-update-db-schema} service (this application no longer owns or migrates its schema).
 */
final class DatabaseSchema {

    /** The schema service's changelog, next to this module in the repository. */
    static final Path CHANGELOG_DIRECTORY = Path.of("..", "account-update-db-schema", "changelog").toAbsolutePath().normalize();
    static final String MASTER_CHANGELOG = "db.changelog-master.yaml";

    private DatabaseSchema() {
    }

    static void apply(PostgreSQLContainer postgres) {
        if (!Files.isRegularFile(CHANGELOG_DIRECTORY.resolve(MASTER_CHANGELOG))) {
            throw new IllegalStateException("Schema changelog not found at " + CHANGELOG_DIRECTORY
                    + ": run the tests from a full checkout of the repository");
        }
        try (Connection connection = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             DirectoryResourceAccessor changelogs = new DirectoryResourceAccessor(CHANGELOG_DIRECTORY)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), changelogs), () ->
                    new CommandScope("update")
                            .addArgumentValue("database", database)
                            .addArgumentValue("changelogFile", MASTER_CHANGELOG)
                            .execute());
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not apply the schema changelog from " + CHANGELOG_DIRECTORY, e);
        }
    }
}

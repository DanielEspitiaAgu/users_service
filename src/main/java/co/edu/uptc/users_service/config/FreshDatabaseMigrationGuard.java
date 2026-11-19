package co.edu.uptc.users_service.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;

/** Guards the opt-in fresh-database profile before Flyway can change the schema. */
public final class FreshDatabaseMigrationGuard {

    private static final String CONFIRMATION_PREFIX = "INITIALIZE_FRESH_DATABASE:";
    private static final Set<String> EXPECTED_VERSIONS = Set.of("1", "2", "3");

    private FreshDatabaseMigrationGuard() {
    }

    public static void migrate(
            DataSource dataSource, Flyway flyway, String expectedDatabase, String confirmation) {
        if (expectedDatabase == null || expectedDatabase.isBlank()) {
            throw new IllegalStateException("USERS_FLYWAY_FRESH_DATABASE_NAME must identify the approved database");
        }
        if (! (CONFIRMATION_PREFIX + expectedDatabase).equals(confirmation)) {
            throw new IllegalStateException(
                    "Set USERS_FLYWAY_FRESH_CONFIRMATION to INITIALIZE_FRESH_DATABASE:<database-name> "
                            + "to authorize this explicit initialization");
        }

        try (Connection connection = dataSource.getConnection()) {
            String[] target = readDatabaseAndSchema(connection);
            if (!expectedDatabase.equals(target[0])) {
                throw new IllegalStateException("Flyway target database does not match the approved database name");
            }

            String configuredSchema = flyway.getConfiguration().getDefaultSchema();
            String targetSchema = configuredSchema == null || configuredSchema.isBlank()
                    ? target[1]
                    : configuredSchema;
            if (!targetSchema.equals(target[1])) {
                throw new IllegalStateException("Flyway default schema does not match the connection's current schema");
            }

            if (tableExists(connection, targetSchema, "flyway_schema_history")) {
                requireCompleteManagedSchema(flyway);
            } else {
                requireEmptySchema(connection, targetSchema);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not verify the approved Flyway target before migration", error);
        }

        flyway.migrate();
    }

    private static String[] readDatabaseAndSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT current_database(), current_schema()")) {
            if (!result.next() || result.getString(1) == null || result.getString(2) == null) {
                throw new IllegalStateException("PostgreSQL did not report a current database and schema");
            }
            return new String[] { result.getString(1), result.getString(2) };
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (ResultSet result = connection.getMetaData().getTables(
                connection.getCatalog(), schema, table, new String[] { "TABLE" })) {
            return result.next();
        }
    }

    private static void requireEmptySchema(Connection connection, String schema) throws SQLException {
        String sql = "SELECT c.relname FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f') "
                + "AND c.relname <> 'flyway_schema_history' LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "Refusing Flyway initialization: target schema is not empty (contains "
                                    + result.getString(1) + ")");
                }
            }
        }
    }

    private static void requireCompleteManagedSchema(Flyway flyway) {
        MigrationInfo[] applied = flyway.info().applied();
        Set<String> appliedVersions = Stream.of(applied)
                .filter(info -> info.getVersion() != null)
                .map(info -> info.getVersion().getVersion())
                .collect(Collectors.toSet());
        boolean allSuccessful = Stream.of(applied)
                .allMatch(info -> info.getState() == MigrationState.SUCCESS);
        if (!allSuccessful || !EXPECTED_VERSIONS.equals(appliedVersions) || flyway.info().pending().length != 0) {
            throw new IllegalStateException(
                    "Refusing Flyway startup: existing schema history is partial or differs from V1-V3");
        }
        flyway.validate();
    }
}

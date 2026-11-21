package co.edu.uptc.users_service.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final List<ColumnRequirement> REQUIRED_COLUMNS = List.of(
            new ColumnRequirement("users", "id", "bigint", null, false),
            new ColumnRequirement("users", "experience", "integer", null, false),
            new ColumnRequirement("user_results", "id", "bigint", null, false),
            new ColumnRequirement("user_results", "user_id", "bigint", null, false),
            new ColumnRequirement("user_results", "match_id", "character varying", 255, true),
            new ColumnRequirement("user_results", "case_id", "character varying", 255, false),
            new ColumnRequirement("user_results", "mode", "character varying", 20, false),
            new ColumnRequirement("user_results", "difficulty", "character varying", 10, true),
            new ColumnRequirement("user_results", "won", "boolean", null, false),
            new ColumnRequirement("user_results", "time_seconds", "integer", null, false),
            new ColumnRequirement("user_results", "xp_delta", "integer", null, false),
            new ColumnRequirement("user_results", "first_win_rewarded", "boolean", null, false),
            new ColumnRequirement("user_results", "created_at", "timestamp without time zone", null, false),
            new ColumnRequirement("classic_case_rewards", "user_id", "bigint", null, false),
            new ColumnRequirement("classic_case_rewards", "case_id", "character varying", 255, false),
            new ColumnRequirement("classic_case_rewards", "mode", "character varying", 20, false),
            new ColumnRequirement("classic_case_rewards", "first_result_id", "bigint", null, false),
            new ColumnRequirement("classic_case_rewards", "xp_awarded", "integer", null, false),
            new ColumnRequirement("classic_case_rewards", "created_at", "timestamp without time zone", null, false));

    private FreshDatabaseMigrationGuard() {
    }

    public static void migrate(
            DataSource dataSource, Flyway flyway, String expectedDatabase, String confirmation) {
        if (expectedDatabase == null || expectedDatabase.isBlank()) {
            throw new IllegalStateException("USERS_FLYWAY_FRESH_DATABASE_NAME must identify the approved database");
        }
        if (!(CONFIRMATION_PREFIX + expectedDatabase).equals(confirmation)) {
            throw new IllegalStateException(
                    "Set USERS_FLYWAY_FRESH_CONFIRMATION to INITIALIZE_FRESH_DATABASE:<database-name> "
                            + "to authorize this explicit initialization");
        }

        String targetSchema;
        try (Connection connection = dataSource.getConnection()) {
            String[] target = readDatabaseAndSchema(connection);
            if (!expectedDatabase.equals(target[0])) {
                throw new IllegalStateException("Flyway target database does not match the approved database name");
            }

            String configuredSchema = flyway.getConfiguration().getDefaultSchema();
            targetSchema = configuredSchema == null || configuredSchema.isBlank()
                    ? target[1]
                    : configuredSchema;
            if (!targetSchema.equals(target[1])) {
                throw new IllegalStateException("Flyway default schema does not match the connection's current schema");
            }

            if (tableExists(connection, targetSchema, "flyway_schema_history")) {
                requireCompleteManagedSchema(flyway);
                requirePhysicalSchema(connection, targetSchema);
            } else {
                requireEmptySchema(connection, targetSchema);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not verify the approved Flyway target before migration", error);
        }

        flyway.migrate();
        try (Connection connection = dataSource.getConnection()) {
            requirePhysicalSchema(connection, targetSchema);
        } catch (SQLException error) {
            throw new IllegalStateException("Could not verify the migrated PostgreSQL schema", error);
        }
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

    private static void requirePhysicalSchema(Connection connection, String schema) throws SQLException {
        requireTable(connection, schema, "users");
        requireTable(connection, schema, "user_results");
        requireTable(connection, schema, "classic_case_rewards");
        for (ColumnRequirement column : REQUIRED_COLUMNS) {
            requireColumn(connection, schema, column);
        }

        requireUniqueKey(connection, schema, "users", Set.of("id"), true);
        requireUniqueKey(connection, schema, "user_results", Set.of("user_id", "match_id"), false);
        requireUniqueKey(connection, schema, "classic_case_rewards", Set.of("user_id", "case_id", "mode"), true);
        requireUniqueKey(connection, schema, "classic_case_rewards", Set.of("first_result_id"), false);

        requireForeignKey(connection, schema, "user_results", Set.of("user_id"), "users", Set.of("id"));
        requireForeignKey(connection, schema, "classic_case_rewards", Set.of("user_id"), "users", Set.of("id"));
        requireForeignKey(connection, schema, "classic_case_rewards", Set.of("first_result_id"),
                "user_results", Set.of("id"));

        requireCheck(connection, schema, "classic_case_rewards", "ck_classic_case_rewards_mode",
                "mode", "=", "classic");
        requireCheck(connection, schema, "classic_case_rewards", "ck_classic_case_rewards_xp",
                "xp_awarded", ">=", "0");
        requireCheck(connection, schema, "user_results", "ck_user_results_first_win_rewarded",
                "first_win_rewarded", "won", "classic");
        requireCheck(connection, schema, "user_results", "ck_user_results_match_id_nonblank",
                "match_id", "btrim");
        requireCheck(connection, schema, "user_results", "ck_user_results_difficulty",
                "difficulty", "easy", "medium", "hard");
    }

    private static void requireTable(Connection connection, String schema, String table) throws SQLException {
        if (!tableExists(connection, schema, table)) {
            throw missing("table " + schema + "." + table);
        }
    }

    private static void requireColumn(Connection connection, String schema, ColumnRequirement expected)
            throws SQLException {
        String sql = "SELECT data_type, character_maximum_length, is_nullable "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, expected.table());
            statement.setString(3, expected.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw missing("column " + schema + "." + expected.table() + "." + expected.name());
                }
                String actualType = result.getString("data_type");
                int actualLength = result.getInt("character_maximum_length");
                boolean actualNullable = "YES".equals(result.getString("is_nullable"));
                if (!expected.type().equals(actualType)
                        || (expected.length() != null && expected.length() != actualLength)
                        || expected.nullable() != actualNullable) {
                    throw new IllegalStateException("Refusing Flyway startup: required PostgreSQL object is "
                            + "incompatible: column " + schema + "." + expected.table() + "."
                            + expected.name() + ": expected " + describe(expected) + ", found "
                            + actualType + (actualLength > 0 ? "(" + actualLength + ")" : "")
                            + (actualNullable ? " nullable" : " not null"));
                }
            }
        }
    }

    private static String describe(ColumnRequirement expected) {
        return expected.type() + (expected.length() == null ? "" : "(" + expected.length() + ")")
                + (expected.nullable() ? " nullable" : " not null");
    }

    private static void requireUniqueKey(
            Connection connection, String schema, String table, Set<String> expectedColumns, boolean primary)
            throws SQLException {
        String sql = "SELECT c.conname, c.contype, c.convalidated, c.condeferrable, "
                + "i.indisvalid, i.indisready, i.indisunique, i.indimmediate, a.attname "
                + "FROM pg_catalog.pg_constraint c "
                + "JOIN pg_catalog.pg_class t ON t.oid = c.conrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace "
                + "JOIN pg_catalog.pg_index i ON i.indexrelid = c.conindid "
                + "JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(attnum, position) ON true "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
                + "WHERE n.nspname = ? AND t.relname = ? AND c.contype IN ('p', 'u')";
        Map<String, Set<String>> columnsByConstraint = new HashMap<>();
        Map<String, boolean[]> validByConstraint = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString("conname");
                    columnsByConstraint.computeIfAbsent(name, ignored -> new HashSet<>())
                            .add(result.getString("attname"));
                    validByConstraint.put(name, new boolean[] {
                            "p".equals(result.getString("contype")),
                            result.getBoolean("convalidated"),
                            !result.getBoolean("condeferrable"),
                            result.getBoolean("indisvalid"),
                            result.getBoolean("indisready"),
                            result.getBoolean("indisunique"),
                            result.getBoolean("indimmediate")
                    });
                }
            }
        }
        boolean found = columnsByConstraint.entrySet().stream().anyMatch(entry -> {
            boolean[] valid = validByConstraint.get(entry.getKey());
            return expectedColumns.equals(entry.getValue()) && valid[0] == primary
                    && valid[1] && valid[2] && valid[3] && valid[4] && valid[5] && valid[6];
        });
        if (!found) {
            throw missing((primary ? "primary key " : "unique constraint ") + schema + "." + table
                    + expectedColumns);
        }
    }

    private static void requireForeignKey(
            Connection connection, String schema, String table, Set<String> columns,
            String referencedTable, Set<String> referencedColumns) throws SQLException {
        String sql = "SELECT c.conname, c.convalidated, c.condeferrable, c.confdeltype, c.confupdtype, "
                + "src.attname AS source_column, "
                + "dst_ns.nspname AS target_schema, dst.relname AS target_table, dst_att.attname AS target_column "
                + "FROM pg_catalog.pg_constraint c "
                + "JOIN pg_catalog.pg_class src_table ON src_table.oid = c.conrelid "
                + "JOIN pg_catalog.pg_namespace src_ns ON src_ns.oid = src_table.relnamespace "
                + "JOIN pg_catalog.pg_class dst ON dst.oid = c.confrelid "
                + "JOIN pg_catalog.pg_namespace dst_ns ON dst_ns.oid = dst.relnamespace "
                + "JOIN LATERAL unnest(c.conkey) WITH ORDINALITY src_key(attnum, position) ON true "
                + "JOIN LATERAL unnest(c.confkey) WITH ORDINALITY dst_key(attnum, position) "
                + "ON dst_key.position = src_key.position "
                + "JOIN pg_catalog.pg_attribute src ON src.attrelid = src_table.oid AND src.attnum = src_key.attnum "
                + "JOIN pg_catalog.pg_attribute dst_att ON dst_att.attrelid = dst.oid AND dst_att.attnum = dst_key.attnum "
                + "WHERE c.contype = 'f' AND src_ns.nspname = ? AND src_table.relname = ?";
        Map<String, ForeignKeyColumns> keys = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString("conname");
                    ForeignKeyColumns key = keys.get(name);
                    if (key == null) {
                        key = new ForeignKeyColumns(result.getString("target_schema"),
                                result.getString("target_table"), result.getBoolean("convalidated"),
                                result.getBoolean("condeferrable"), result.getString("confdeltype"),
                                result.getString("confupdtype"));
                        keys.put(name, key);
                    }
                    key.sourceColumns.add(result.getString("source_column"));
                    key.targetColumns.add(result.getString("target_column"));
                }
            }
        }
        boolean found = keys.values().stream().anyMatch(key -> key.validated
                && !key.deferrable && "a".equals(key.deleteAction) && "a".equals(key.updateAction)
                && schema.equals(key.targetSchema) && referencedTable.equals(key.targetTable)
                && columns.equals(key.sourceColumns) && referencedColumns.equals(key.targetColumns));
        if (!found) {
            throw missing("foreign key " + schema + "." + table + columns + " -> "
                    + schema + "." + referencedTable + referencedColumns);
        }
    }

    private static void requireCheck(
            Connection connection, String schema, String table, String constraint, String... requiredFragments)
            throws SQLException {
        String sql = "SELECT pg_catalog.pg_get_constraintdef(c.oid, true) "
                + "FROM pg_catalog.pg_constraint c "
                + "JOIN pg_catalog.pg_class t ON t.oid = c.conrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace "
                + "WHERE n.nspname = ? AND t.relname = ? AND c.conname = ? "
                + "AND c.contype = 'c' AND c.convalidated";
        List<String> definitions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, constraint);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    definitions.add(normalizeDefinition(result.getString(1)));
                }
            }
        }
        boolean found = definitions.stream().anyMatch(definition -> Stream.of(requiredFragments)
                .allMatch(fragment -> definition.contains(normalizeDefinition(fragment))));
        if (!found) {
            throw missing("validated check constraint on " + schema + "." + table
                    + " named " + constraint + " containing " + String.join(", ", requiredFragments));
        }
    }

    private static String normalizeDefinition(String value) {
        return value.toLowerCase(Locale.ROOT).replace("\"", "").replaceAll("\\s+", "");
    }

    private static IllegalStateException missing(String object) {
        return new IllegalStateException("Refusing Flyway startup: required PostgreSQL object is missing or altered: "
                + object);
    }

    private record ColumnRequirement(String table, String name, String type, Integer length, boolean nullable) {
    }

    private static final class ForeignKeyColumns {
        private final String targetSchema;
        private final String targetTable;
        private final boolean validated;
        private final boolean deferrable;
        private final String deleteAction;
        private final String updateAction;
        private final Set<String> sourceColumns = new HashSet<>();
        private final Set<String> targetColumns = new HashSet<>();

        private ForeignKeyColumns(String targetSchema, String targetTable, boolean validated,
                boolean deferrable, String deleteAction, String updateAction) {
            this.targetSchema = targetSchema;
            this.targetTable = targetTable;
            this.validated = validated;
            this.deferrable = deferrable;
            this.deleteAction = deleteAction;
            this.updateAction = updateAction;
        }
    }
}

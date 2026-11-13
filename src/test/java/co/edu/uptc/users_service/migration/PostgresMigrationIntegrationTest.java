package co.edu.uptc.users_service.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresMigrationIntegrationTest {

    private static String jdbcUrl;
    private static String dbUser;
    private static String dbPassword;
    private static String instanceMarker;

    private final List<String> createdSchemas = new ArrayList<>();

    @BeforeAll
    static void requireIsolatedPostgres16() throws Exception {
        jdbcUrl = System.getenv("MIGRATION_TEST_JDBC_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException(
                    "Set MIGRATION_TEST_JDBC_URL by using tests/run_postgres_migrations.ps1");
        }
        dbUser = System.getenv().getOrDefault("MIGRATION_TEST_DB_USER", "postgres");
        dbPassword = System.getenv().getOrDefault("MIGRATION_TEST_DB_PASSWORD", "");
        instanceMarker = System.getenv("MIGRATION_TEST_INSTANCE_MARKER");
        if (instanceMarker == null || instanceMarker.isBlank()) {
            throw new IllegalStateException("Run this test through tests/run_postgres_migrations.ps1");
        }

        if (!jdbcUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/migration_test(?:\\?.*)?")) {
            throw new IllegalStateException("Migration tests only accept a loopback migration_test database");
        }

        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW server_version")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).startsWith("16.15"),
                    "Expected isolated PostgreSQL 16.15, found " + result.getString(1));
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT shobj_description(oid, 'pg_database') "
                        + "FROM pg_database WHERE datname = current_database()")) {
            assertTrue(result.next());
            assertEquals(instanceMarker, result.getString(1),
                    "The database was not marked by this disposable test runner");
        }
    }

    @AfterEach
    void dropOnlySchemasCreatedByThisTest() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String schema : createdSchemas) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        } finally {
            createdSchemas.clear();
        }
    }

    @Test
    void newDatabaseRunsV1ThenV2AndEnforcesUniqueKeys() throws Exception {
        String schema = createSchema("m61b2_new_");
        Flyway flyway = flyway(schema, "classpath:db/migration");

        flyway.migrate();

        assertEquals(List.of("1", "2", "3"), appliedVersions(schema));
        assertTrue(columnExists(schema, "user_results", "match_id"));
        assertTrue(columnExists(schema, "user_results", "first_win_rewarded"));
        assertTrue(columnExists(schema, "user_results", "difficulty"));
        assertTrue(tableExists(schema, "classic_case_rewards"));
        assertTrue(constraintExists(schema, "user_results", "uq_user_results_user_match"));
        assertTrue(constraintExists(schema, "classic_case_rewards", "pk_classic_case_rewards"));

        long userId = insertUser(schema, "new-user");
        long firstResult = insertResult(schema, userId, "new-case", "CLASSIC", true, 90, 0, "match-1");
        insertResult(schema, userId, "new-case", "CLASSIC", true, 91, 0, "match-2");

        assertThrows(SQLException.class,
                () -> insertResult(schema, userId, "other-case", "CLASSIC", true, 92, 0, "match-1"));

        insertReward(schema, userId, "new-case", "CLASSIC", firstResult, 50);
        long secondResult = resultId(schema, userId, "match-2");
        assertThrows(SQLException.class,
                () -> insertReward(schema, userId, "new-case", "CLASSIC", secondResult, 0));
    }

    @Test
    void v1ReproducesTheConfirmedLegacyColumnsAndConstraints() throws Exception {
        String migrationSchema = createSchema("m61b2_v1_");
        String legacySchema = createSchema("m61b2_fixture_");

        Flyway.configure()
                .dataSource(jdbcUrl, dbUser, dbPassword)
                .schemas(migrationSchema)
                .defaultSchema(migrationSchema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
        applySqlResource(legacySchema, "db/legacy-schema.sql");

        assertEquals(schemaSignature(legacySchema), schemaSignature(migrationSchema));
    }

    @Test
    void existingCompatibleSchemaCanBeBaselinedAndHistoricalRowsArePreserved() throws Exception {
        String schema = createSchema("m61b2_existing_");
        applySqlResource(schema, "db/legacy-schema.sql");
        long userId = seedObservedHistory(schema);
        List<String> before = legacyResultRows(schema);
        Map<String, Long> beforeGroups = legacyResultGroups(schema);

        Flyway baseline = Flyway.configure()
                .dataSource(jdbcUrl, dbUser, dbPassword)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .baselineDescription("Verified ddl-auto schema")
                .load();
        baseline.baseline();
        baseline.migrate();

        assertEquals(List.of("1", "2", "3"), appliedVersions(schema));
        assertEquals(before, legacyResultRows(schema));
        assertEquals(beforeGroups, legacyResultGroups(schema));
        assertEquals(19, before.size());
        assertEquals(Map.of(
                "TIMED|true", 6L,
                "TIMED|false", 4L,
                "VERSUS|true", 4L,
                "VERSUS|false", 4L,
                "timed|true", 1L), beforeGroups);
        assertEquals(19, count(schema, "user_results"));
        assertEquals(1, count(schema, "achievements"));
        assertEquals(1, count(schema, "user_achievements"));
        assertEquals(19, scalarLong("SELECT count(*) FROM \"" + schema
                + "\".user_results WHERE match_id IS NULL AND first_win_rewarded = false"));
        assertEquals(19, scalarLong("SELECT count(*) FROM \"" + schema
                + "\".user_results WHERE difficulty IS NULL"));
        assertEquals(0, scalarLong("SELECT count(*) FROM \"" + schema
                + "\".classic_case_rewards WHERE user_id = " + userId));
    }

    @Test
    void failedPostgresMigrationRollsBackAndCanBeRecovered() throws Exception {
        String schema = createSchema("m61b2_recovery_");
        applySqlResource(schema, "db/legacy-schema.sql");
        seedObservedHistory(schema);
        List<String> before = legacyResultRows(schema);
        Flyway baseline = Flyway.configure()
                .dataSource(jdbcUrl, dbUser, dbPassword)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .load();
        baseline.baseline();

        Flyway failingMigration = Flyway.configure()
                .dataSource(jdbcUrl, dbUser, dbPassword)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/failing-migration")
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .load();
        assertThrows(FlywayException.class, failingMigration::migrate);

        assertFalse(columnExists(schema, "user_results", "rollback_probe"));
        assertEquals(List.of("1"), appliedVersions(schema));
        assertEquals(before, legacyResultRows(schema));

        flyway(schema, "classpath:db/migration").migrate();
        assertTrue(columnExists(schema, "user_results", "match_id"));
        assertEquals(List.of("1", "2", "3"), appliedVersions(schema));
        assertEquals(before, legacyResultRows(schema));
    }

    private static Flyway flyway(String schema, String location) {
        return Flyway.configure()
                .dataSource(jdbcUrl, dbUser, dbPassword)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(location)
                .load();
    }

    private String createSchema(String prefix) throws SQLException {
        String schema = prefix + UUID.randomUUID().toString().replace("-", "");
        createdSchemas.add(schema);
        execute("CREATE SCHEMA \"" + schema + "\"");
        return schema;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void applySqlResource(String schema, String resource) throws SQLException, IOException {
        try (InputStream input = PostgresMigrationIntegrationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test SQL resource: " + resource);
            }
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO \"" + schema + "\"");
                for (String sql : script.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    private static List<String> schemaSignature(String schema) throws SQLException {
        List<String> signature = new ArrayList<>();
        String columnsSql = "SELECT table_name, column_name, data_type, "
                + "COALESCE(character_maximum_length, 0), is_nullable, "
                + "COALESCE(datetime_precision, 0), COALESCE(identity_generation, '') "
                + "FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name IN ('users', 'user_results', 'achievements', 'user_achievements') "
                + "ORDER BY table_name, ordinal_position";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(columnsSql)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    signature.add("COLUMN|" + result.getString(1) + "|" + result.getString(2) + "|"
                            + result.getString(3) + "|" + result.getInt(4) + "|" + result.getString(5) + "|"
                            + result.getInt(6) + "|" + result.getString(7));
                }
            }
        }

        String constraintsSql = "SELECT tc.table_name, tc.constraint_type, "
                + "(SELECT string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position) "
                + "FROM information_schema.key_column_usage kcu "
                + "WHERE kcu.constraint_schema = tc.constraint_schema "
                + "AND kcu.constraint_name = tc.constraint_name AND kcu.table_name = tc.table_name), "
                + "COALESCE((SELECT max(ccu.table_name) FROM information_schema.constraint_column_usage ccu "
                + "WHERE ccu.constraint_schema = tc.constraint_schema "
                + "AND ccu.constraint_name = tc.constraint_name), ''), "
                + "COALESCE((SELECT string_agg(ccu.column_name, ',' ORDER BY ccu.column_name) "
                + "FROM information_schema.constraint_column_usage ccu "
                + "WHERE ccu.constraint_schema = tc.constraint_schema "
                + "AND ccu.constraint_name = tc.constraint_name), '') "
                + "FROM information_schema.table_constraints tc "
                + "WHERE tc.table_schema = ? AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY') "
                + "AND tc.table_name IN ('users', 'user_results', 'achievements', 'user_achievements') "
                + "ORDER BY tc.table_name, tc.constraint_type, 3";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(constraintsSql)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    signature.add("CONSTRAINT|" + result.getString(1) + "|" + result.getString(2) + "|"
                            + result.getString(3) + "|" + result.getString(4) + "|" + result.getString(5));
                }
            }
        }
        return signature;
    }

    private static long insertUser(String schema, String suffix) throws SQLException {
        String sql = "INSERT INTO \"" + schema + "\".users "
                + "(external_id, language, tutorial_completed, experience, streak, created_at, updated_at) "
                + "VALUES (?, 'es', false, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "migration-test-" + suffix);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static long insertResult(String schema, long userId, String caseId, String mode,
            boolean won, int seconds, int xp, String matchId) throws SQLException {
        String sql = "INSERT INTO \"" + schema + "\".user_results "
                + "(user_id, case_id, mode, won, time_seconds, xp_delta, created_at, match_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?) RETURNING id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, caseId);
            statement.setString(3, mode);
            statement.setBoolean(4, won);
            statement.setInt(5, seconds);
            statement.setInt(6, xp);
            statement.setString(7, matchId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static void insertReward(String schema, long userId, String caseId,
            String mode, long resultId, int xp) throws SQLException {
        String sql = "INSERT INTO \"" + schema + "\".classic_case_rewards "
                + "(user_id, case_id, mode, first_result_id, xp_awarded, created_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, caseId);
            statement.setString(3, mode);
            statement.setLong(4, resultId);
            statement.setInt(5, xp);
            statement.executeUpdate();
        }
    }

    private static long resultId(String schema, long userId, String matchId) throws SQLException {
        String sql = "SELECT id FROM \"" + schema + "\".user_results WHERE user_id = ? AND match_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, matchId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static long seedObservedHistory(String schema) throws SQLException {
        long userId = insertUser(schema, "legacy-user");
        execute("INSERT INTO \"" + schema + "\".achievements "
                + "(code, name, description, icon, requirement) "
                + "VALUES ('TEST_LEGACY', 'Legacy', 'Migration fixture', NULL, 'WINS>=1')");
        execute("INSERT INTO \"" + schema + "\".user_achievements "
                + "(user_id, achievement_id, unlocked_at) SELECT " + userId
                + ", id, CURRENT_TIMESTAMP FROM \"" + schema
                + "\".achievements WHERE code = 'TEST_LEGACY'");

        for (int i = 0; i < 10; i++) {
            insertLegacyResult(schema, userId, "TIMED", i < 6, i);
        }
        for (int i = 0; i < 8; i++) {
            insertLegacyResult(schema, userId, "VERSUS", i < 4, i + 10);
        }
        insertLegacyResult(schema, userId, "timed", true, 18);
        return userId;
    }

    private static void insertLegacyResult(String schema, long userId,
            String mode, boolean won, int index) throws SQLException {
        String sql = "INSERT INTO \"" + schema + "\".user_results "
                + "(user_id, case_id, mode, won, time_seconds, xp_delta, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, "legacy-case-" + index);
            statement.setString(3, mode);
            statement.setBoolean(4, won);
            statement.setInt(5, 60 + index);
            statement.setInt(6, won ? 5 + index : 0);
            statement.executeUpdate();
        }
    }

    private static List<String> legacyResultRows(String schema) throws SQLException {
        String sql = "SELECT id, user_id, case_id, mode, won, time_seconds, xp_delta, created_at "
                + "FROM \"" + schema + "\".user_results ORDER BY id";
        List<String> rows = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getLong("id") + "|" + result.getLong("user_id") + "|"
                        + result.getString("case_id") + "|" + result.getString("mode") + "|"
                        + result.getBoolean("won") + "|" + result.getInt("time_seconds") + "|"
                        + result.getInt("xp_delta") + "|" + result.getTimestamp("created_at"));
            }
        }
        return rows;
    }

    private static Map<String, Long> legacyResultGroups(String schema) throws SQLException {
        String sql = "SELECT mode, won, count(*) FROM \"" + schema
                + "\".user_results GROUP BY mode, won ORDER BY mode, won";
        Map<String, Long> groups = new HashMap<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                groups.put(result.getString(1) + "|" + result.getBoolean(2), result.getLong(3));
            }
        }
        return groups;
    }

    private static List<String> appliedVersions(String schema) throws SQLException {
        String sql = "SELECT version FROM \"" + schema
                + "\".flyway_schema_history WHERE success ORDER BY installed_rank";
        List<String> versions = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                versions.add(result.getString(1));
            }
        }
        return versions;
    }

    private static long count(String schema, String table) throws SQLException {
        return scalarLong("SELECT count(*) FROM \"" + schema + "\".\"" + table + "\"");
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static boolean tableExists(String schema, String table) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = ?)";
        return scalarExists(sql, schema, table);
    }

    private static boolean columnExists(String schema, String table, String column) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?)";
        return scalarExists(sql, schema, table, column);
    }

    private static boolean constraintExists(String schema, String table, String constraint) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM information_schema.table_constraints "
                + "WHERE table_schema = ? AND table_name = ? AND constraint_name = ?)";
        return scalarExists(sql, schema, table, constraint);
    }

    private static boolean scalarExists(String sql, String... values) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }
}

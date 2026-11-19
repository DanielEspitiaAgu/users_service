package co.edu.uptc.users_service.migration;

import co.edu.uptc.users_service.UsersServiceApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshDatabaseFlywayProfilePostgresTest {

    private static final String DATABASE = "migration_test";
    private static final String CONFIRMATION = "INITIALIZE_FRESH_DATABASE:" + DATABASE;

    @Test
    void normalConfigurationDoesNotEnableFlyway() throws Exception {
        Properties properties = new Properties();
        try (var input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertTrue(input != null);
            properties.load(input);
        }
        assertEquals("false", properties.getProperty("spring.flyway.enabled"));
        assertEquals("${JPA_DDL_AUTO:update}", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
    }

    @Test
    void freshProfileMigratesValidatesAndCanRestartWithoutDuplicateMigrations() throws Exception {
        String baseUrl = requiredEnv("MIGRATION_TEST_JDBC_URL");
        String dbUser = System.getenv().getOrDefault("MIGRATION_TEST_DB_USER", "postgres");
        String dbPassword = System.getenv().getOrDefault("MIGRATION_TEST_DB_PASSWORD", "");
        String schema = createSchema(baseUrl, dbUser, dbPassword, "fresh_profile_");
        String schemaUrl = baseUrl + "?currentSchema=" + schema;

        String[] args = startupArguments(schemaUrl, schema, dbUser, dbPassword);
        try {
            verifyApplicationStartup(args);
            verifyApplicationStartup(args);
            assertEquals(3, scalar(baseUrl, dbUser, dbPassword,
                    "SELECT count(*) FROM \"" + schema + "\".flyway_schema_history WHERE success"));
            assertTrue(tableExists(baseUrl, dbUser, dbPassword, schema, "classic_case_rewards"));
        } finally {
            execute(baseUrl, dbUser, dbPassword, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    @Test
    void guardRejectsIncompatibleSchemaWithoutChangingIt() throws Exception {
        String baseUrl = requiredEnv("MIGRATION_TEST_JDBC_URL");
        String dbUser = System.getenv().getOrDefault("MIGRATION_TEST_DB_USER", "postgres");
        String dbPassword = System.getenv().getOrDefault("MIGRATION_TEST_DB_PASSWORD", "");
        String schema = createSchema(baseUrl, dbUser, dbPassword, "fresh_incompatible_");
        String schemaUrl = baseUrl + "?currentSchema=" + schema;
        execute(baseUrl, dbUser, dbPassword,
                "CREATE TABLE \"" + schema + "\".existing_marker (id integer PRIMARY KEY)");

        try {
            var dataSource = new DriverManagerDataSource(schemaUrl, dbUser, dbPassword);
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load();

            assertThrows(IllegalStateException.class,
                    () -> co.edu.uptc.users_service.config.FreshDatabaseMigrationGuard.migrate(
                            dataSource, flyway, DATABASE, CONFIRMATION));
            assertTrue(tableExists(baseUrl, dbUser, dbPassword, schema, "existing_marker"));
            assertEquals(0, scalar(baseUrl, dbUser, dbPassword,
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + schema
                            + "' AND table_name IN ('users', 'user_results', 'classic_case_rewards', "
                            + "'flyway_schema_history')"));
        } finally {
            execute(baseUrl, dbUser, dbPassword, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    private static void verifyApplicationStartup(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(UsersServiceApplication.class)
                .profiles("flyway-fresh")
                .web(WebApplicationType.SERVLET)
                .run(args)) {
            Flyway flyway = context.getBean(Flyway.class);
            assertEquals(List.of("1", "2", "3"),
                    java.util.Arrays.stream(flyway.info().applied())
                            .map(info -> info.getVersion().getVersion())
                            .toList());
            assertEquals("validate", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
        }
    }

    private static String[] startupArguments(String url, String schema, String dbUser, String dbPassword) {
        return new String[] {
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + dbUser,
                "--spring.datasource.password=" + dbPassword,
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.default-schema=" + schema,
                "--users.flyway.fresh.database-name=" + DATABASE,
                "--users.flyway.fresh.confirmation=" + CONFIRMATION,
                "--eureka.client.enabled=false",
                "--eureka.client.register-with-eureka=false",
                "--eureka.client.fetch-registry=false",
                "--spring.kafka.listener.auto-startup=false",
                "--server.port=0",
                "--spring.main.banner-mode=off"
        };
    }

    private static String createSchema(String url, String user, String password, String prefix) throws Exception {
        String schema = prefix + UUID.randomUUID().toString().replace("-", "");
        execute(url, user, password, "CREATE SCHEMA \"" + schema + "\"");
        return schema;
    }

    private static boolean tableExists(String url, String user, String password, String schema, String table)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), schema, table,
                        new String[] { "TABLE" })) {
            return result.next();
        }
    }

    private static int scalar(String url, String user, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void execute(String url, String user, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing isolated PostgreSQL test variable: " + key);
        }
        if (!value.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/migration_test")) {
            throw new IllegalStateException("Fresh profile test requires the isolated migration_test database");
        }
        String marker = System.getenv("MIGRATION_TEST_INSTANCE_MARKER");
        if (marker == null || marker.isBlank()) {
            throw new IllegalStateException("Missing isolated PostgreSQL instance marker");
        }
        try (Connection connection = DriverManager.getConnection(value,
                System.getenv().getOrDefault("MIGRATION_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("MIGRATION_TEST_DB_PASSWORD", ""));
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT shobj_description(oid, 'pg_database') "
                        + "FROM pg_database WHERE datname = current_database()")) {
            if (!result.next() || !marker.equals(result.getString(1))) {
                throw new IllegalStateException("PostgreSQL database is not marked by the isolated test runner");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not verify isolated PostgreSQL database", error);
        }
        return value;
    }
}

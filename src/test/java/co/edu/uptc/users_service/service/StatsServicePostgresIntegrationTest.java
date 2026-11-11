package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.dto.ResultDTO;
import co.edu.uptc.users_service.dto.ResultResponseDTO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false"
})
class StatsServicePostgresIntegrationTest {

    private static final String SCHEMA = "m61b3_service_it";

    @Autowired
    private StatsService statsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureIsolatedPostgres(DynamicPropertyRegistry registry) {
        String baseUrl = requiredEnv("MIGRATION_TEST_JDBC_URL");
        String dbUser = System.getenv().getOrDefault("MIGRATION_TEST_DB_USER", "postgres");
        String dbPassword = System.getenv().getOrDefault("MIGRATION_TEST_DB_PASSWORD", "");
        String marker = requiredEnv("MIGRATION_TEST_INSTANCE_MARKER");
        if (!baseUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/migration_test(?:\\?.*)?")) {
            throw new IllegalStateException("Integration tests require the disposable migration_test database");
        }

        try (Connection connection = DriverManager.getConnection(baseUrl, dbUser, dbPassword)) {
            try (Statement statement = connection.createStatement();
                    ResultSet version = statement.executeQuery("SHOW server_version")) {
                if (!version.next() || !version.getString(1).startsWith("16.15")) {
                    throw new IllegalStateException("Integration tests require PostgreSQL 16.15");
                }
            }
            try (Statement statement = connection.createStatement();
                    ResultSet databaseMarker = statement.executeQuery(
                            "SELECT shobj_description(oid, 'pg_database') FROM pg_database "
                                    + "WHERE datname = current_database()")) {
                if (!databaseMarker.next() || !marker.equals(databaseMarker.getString(1))) {
                    throw new IllegalStateException("PostgreSQL database is not marked by the isolated test runner");
                }
            }
            try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not prepare the isolated PostgreSQL schema", error);
        }

        Flyway.configure()
                .dataSource(baseUrl, dbUser, dbPassword)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        String schemaUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> dbUser);
        registry.add("spring.datasource.password", () -> dbPassword);
        registry.add("security.gateway-secret", () -> requiredEnv("MIGRATION_TEST_GATEWAY_SECRET"));
        registry.add("internal.api-token", () -> requiredEnv("MIGRATION_TEST_INTERNAL_TOKEN"));
    }

    @BeforeEach
    void clearTestRows() {
        jdbcTemplate.update("DELETE FROM classic_case_rewards");
        jdbcTemplate.update("DELETE FROM user_achievements");
        jdbcTemplate.update("DELETE FROM user_results");
        jdbcTemplate.update("DELETE FROM users");
    }

    @ParameterizedTest
    @CsvSource({ "EASY,50", "MEDIUM,100", "HARD,150" })
    void firstClassicWinAwardsDifficultyAmount(String difficulty, int expectedXp) {
        ResultResponseDTO result = register(classic("easy-hard-match", "case-" + difficulty, true,
                difficulty, 120));

        assertEquals(expectedXp, result.xpDelta());
        assertTrue(result.firstWinRewarded());
        assertEquals(expectedXp, result.stats().experience());
        assertEquals(1, result.stats().uniqueCasesSolved());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
    }

    @Test
    void repeatedClassicVictoryRecordsZeroXp() {
        ResultResponseDTO first = register(classic("classic-first", "case-repeat", true, "EASY", 90));
        ResultResponseDTO repeated = register(classic("classic-repeat", "case-repeat", true, "EASY", 95));

        assertEquals(50, first.xpDelta());
        assertTrue(first.firstWinRewarded());
        assertEquals(0, repeated.xpDelta());
        assertFalse(repeated.firstWinRewarded());
        assertEquals(50, repeated.stats().experience());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
    }

    @Test
    void simultaneousWinsForSameUserAndCaseAwardOnlyOnce() throws Exception {
        statsService.getStats("parallel-player");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResultResponseDTO> first = executor.submit(() -> concurrentRegister(
                    ready, start, classic("parallel-one", "case-parallel", true, "EASY", 100)));
            Future<ResultResponseDTO> second = executor.submit(() -> concurrentRegister(
                    ready, start, classic("parallel-two", "case-parallel", true, "EASY", 101)));
            ready.await();
            start.countDown();

            List<ResultResponseDTO> responses = List.of(first.get(), second.get());
            assertEquals(1, responses.stream().filter(ResultResponseDTO::firstWinRewarded).count());
            assertEquals(50, responses.stream().mapToInt(ResultResponseDTO::xpDelta).sum());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(50, jdbcTemplate.queryForObject(
                "SELECT experience FROM users WHERE external_id = 'parallel-player'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
    }

    @Test
    void differentUsersCanEachClaimTheSameCase() {
        ResultResponseDTO first = statsService.registerResult("player-a",
                classic("player-a-match", "shared-case", true, "MEDIUM", 80));
        ResultResponseDTO second = statsService.registerResult("player-b",
                classic("player-b-match", "shared-case", true, "MEDIUM", 81));

        assertEquals(100, first.xpDelta());
        assertEquals(100, second.xpDelta());
        assertTrue(first.firstWinRewarded());
        assertTrue(second.firstWinRewarded());
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
    }

    @Test
    void identicalMatchRetryReturnsPersistedOutcomeWithoutSideEffects() {
        ResultDTO request = classic("retry-match", "retry-case", true, "HARD", 77);
        ResultResponseDTO original = register(request);

        int resultCountBeforeRetry = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_results r JOIN users u ON u.id = r.user_id "
                        + "WHERE u.external_id = 'integration-player' AND r.match_id = 'retry-match'",
                Integer.class);
        int appliedXpBeforeRetry = jdbcTemplate.queryForObject(
                "SELECT xp_delta FROM user_results r JOIN users u ON u.id = r.user_id "
                        + "WHERE u.external_id = 'integration-player' AND r.match_id = 'retry-match'",
                Integer.class);
        int rewardCountBeforeRetry = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM classic_case_rewards r JOIN users u ON u.id = r.user_id "
                        + "WHERE u.external_id = 'integration-player' AND r.case_id = 'retry-case' "
                        + "AND r.mode = 'CLASSIC'",
                Integer.class);
        int achievementCountBeforeRetry = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_achievements ua JOIN users u ON u.id = ua.user_id "
                        + "WHERE u.external_id = 'integration-player'",
                Integer.class);
        ResultResponseDTO retry = register(request);

        assertEquals(original.matchId(), retry.matchId());
        assertEquals(original.xpDelta(), retry.xpDelta());
        assertEquals(original.firstWinRewarded(), retry.firstWinRewarded());
        assertTrue(retry.newAchievements().isEmpty());
        assertEquals(1, resultCountBeforeRetry);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_results r JOIN users u ON u.id = r.user_id "
                        + "WHERE u.external_id = 'integration-player' AND r.match_id = 'retry-match'",
                Integer.class));
        assertEquals(150, appliedXpBeforeRetry);
        assertEquals(150, jdbcTemplate.queryForObject(
                "SELECT xp_delta FROM user_results r JOIN users u ON u.id = r.user_id "
                        + "WHERE u.external_id = 'integration-player' AND r.match_id = 'retry-match'",
                Integer.class));
        assertEquals(1, rewardCountBeforeRetry);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM classic_case_rewards r JOIN users u ON u.id = r.user_id "
                        + "WHERE u.external_id = 'integration-player' AND r.case_id = 'retry-case' "
                        + "AND r.mode = 'CLASSIC'",
                Integer.class));
        assertEquals(150, jdbcTemplate.queryForObject(
                "SELECT experience FROM users WHERE external_id = 'integration-player'", Integer.class));
        assertEquals(2, achievementCountBeforeRetry,
                "The first win unlocks FIRST_WIN and XP>=100 for a HARD reward");
        assertEquals(achievementCountBeforeRetry, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_achievements ua JOIN users u ON u.id = ua.user_id "
                        + "WHERE u.external_id = 'integration-player'",
                Integer.class));
    }

    @Test
    void contradictoryMatchRetryConflictsWithoutSideEffects() {
        register(classic("conflict-match", "conflict-case", true, "EASY", 90));
        int achievementsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM user_achievements", Integer.class);

        assertThrows(co.edu.uptc.users_service.exception.ResultConflictException.class,
                () -> register(classic("conflict-match", "conflict-case", true, "MEDIUM", 90)));

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM user_results", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
        assertEquals(50, jdbcTemplate.queryForObject("SELECT experience FROM users", Integer.class));
        assertEquals(achievementsBefore,
                jdbcTemplate.queryForObject("SELECT count(*) FROM user_achievements", Integer.class));
    }

    @Test
    void classicDefeatDoesNotClaimRewardAndLaterWinCanClaimIt() {
        ResultResponseDTO loss = register(classic("classic-loss", "loss-then-win", false, "HARD", 130));
        ResultResponseDTO win = register(classic("classic-win", "loss-then-win", true, "HARD", 110));

        assertEquals(0, loss.xpDelta());
        assertFalse(loss.firstWinRewarded());
        assertEquals(150, win.xpDelta());
        assertTrue(win.firstWinRewarded());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
    }

    @Test
    void timedAndVersusKeepInternalXpAndDoNotCreateClassicClaims() {
        ResultResponseDTO timed = statsService.registerResult("mode-player",
                new ResultDTO("timed-match", "timed-case", "TIMED", false, 45, null, -10));
        ResultResponseDTO versus = statsService.registerResult("mode-player",
                new ResultDTO("versus-match", "versus-case", "VERSUS", true, 70, null, 100));

        assertEquals(-10, timed.xpDelta());
        assertEquals(-10, timed.stats().experience());
        assertEquals(100, versus.xpDelta());
        assertEquals(90, versus.stats().experience());
        assertFalse(timed.firstWinRewarded());
        assertFalse(versus.firstWinRewarded());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
    }

    @Test
    void uniqueCasesCountsDistinctWinsAcrossModesAndPreservesSolvedCases() {
        statsService.registerResult("unique-player",
                classic("unique-classic", "same-case", true, "EASY", 60));
        statsService.registerResult("unique-player",
                new ResultDTO("unique-timed", "same-case", "TIMED", true, 40, null, 20));
        ResultResponseDTO other = statsService.registerResult("unique-player",
                new ResultDTO("unique-versus", "another-case", "VERSUS", true, 50, null, 100));
        statsService.registerResult("unique-player",
                new ResultDTO("unique-loss", "lost-case", "TIMED", false, 35, null, 0));

        assertEquals(3, other.stats().solvedCases());
        assertEquals(2, other.stats().uniqueCasesSolved());
    }

    @Test
    void failureAfterResultAndRewardInsertRollsBackAllWrites() {
        createAchievementFailureTrigger();
        try {
            assertThrows(RuntimeException.class,
                    () -> register(classic("rollback-match", "rollback-case", true, "EASY", 90)));
        } finally {
            dropAchievementFailureTrigger();
        }

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM user_results", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM classic_case_rewards", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT experience FROM users", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM user_achievements", Integer.class));
    }

    private ResultResponseDTO register(ResultDTO dto) {
        return statsService.registerResult("integration-player", dto);
    }

    private ResultResponseDTO concurrentRegister(CountDownLatch ready, CountDownLatch start,
            ResultDTO dto) throws InterruptedException {
        ready.countDown();
        start.await();
        return statsService.registerResult("parallel-player", dto);
    }

    private static ResultDTO classic(String matchId, String caseId, boolean won,
            String difficulty, int timeSeconds) {
        return new ResultDTO(matchId, caseId, "CLASSIC", won, timeSeconds, difficulty, null);
    }

    private void createAchievementFailureTrigger() {
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION " + SCHEMA
                + ".fail_test_achievement_insert() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced transaction rollback'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER fail_test_achievement_insert BEFORE INSERT ON " + SCHEMA
                + ".user_achievements FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".fail_test_achievement_insert()");
    }

    private void dropAchievementFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_test_achievement_insert ON " + SCHEMA + ".user_achievements");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + SCHEMA + ".fail_test_achievement_insert()");
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing isolated PostgreSQL test variable: " + key);
        }
        return value;
    }
}

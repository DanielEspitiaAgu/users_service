package co.edu.uptc.users_service.infrastructure.adapter.out.persistence;

import co.edu.uptc.users_service.application.port.in.GetOrCreateProfileUseCase;
import co.edu.uptc.users_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "eureka.client.enabled=false",
        "security.gateway-secret=test-gateway-secret",
        "internal.api-token=test-internal-token",
        "METRICS_SCRAPE_USERNAME=metrics",
        "METRICS_SCRAPE_PASSWORD=test-scrape-password"
})
@EnabledIfEnvironmentVariable(named = "USERS_PROFILE_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/users_profile_test")
class ProfileCreationPostgresIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("USERS_PROFILE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("USERS_PROFILE_TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("USERS_PROFILE_TEST_DB_PASSWORD", ""));
    }

    @Autowired private GetOrCreateProfileUseCase getOrCreate;
    @Autowired private UserRepository userRepository;

    @Test
    void concurrentCreationForSameExternalIdPersistsExactlyOneProfile() throws Exception {
        String externalId = "race-" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createTogether(externalId, ready, start));
            var second = executor.submit(() -> createTogether(externalId, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var firstProfile = first.get(20, TimeUnit.SECONDS);
            var secondProfile = second.get(20, TimeUnit.SECONDS);
            assertEquals(externalId, firstProfile.externalId());
            assertEquals(externalId, secondProfile.externalId());
            assertEquals(1, userRepository.findAll().stream().filter(user -> externalId.equals(user.getExternalId())).count());
        }
    }

    private co.edu.uptc.users_service.domain.model.PlayerProfile createTogether(
            String externalId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
        return getOrCreate.getOrCreate(externalId);
    }
}



package co.edu.uptc.users_service.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("flyway-fresh")
public class FreshDatabaseFlywayConfiguration {

    @Bean
    FlywayMigrationStrategy freshDatabaseOnlyMigrationStrategy(
            DataSource dataSource,
            @Value("${users.flyway.fresh.database-name:}") String expectedDatabase,
            @Value("${users.flyway.fresh.confirmation:}") String confirmation) {
        return flyway -> FreshDatabaseMigrationGuard.migrate(dataSource, flyway, expectedDatabase, confirmation);
    }
}

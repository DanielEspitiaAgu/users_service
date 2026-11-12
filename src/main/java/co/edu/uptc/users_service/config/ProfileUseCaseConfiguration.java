package co.edu.uptc.users_service.config;

import co.edu.uptc.users_service.application.usecase.ProfileUseCase;
import co.edu.uptc.users_service.infrastructure.adapter.out.persistence.JpaProfileAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProfileUseCaseConfiguration {
    @Bean
    ProfileUseCase profileUseCase(JpaProfileAdapter profileStore) {
        return new ProfileUseCase(profileStore);
    }
}

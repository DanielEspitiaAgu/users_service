package co.edu.uptc.users_service.config;

import co.edu.uptc.users_service.entity.Role;
import co.edu.uptc.users_service.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) {
        createRoleIfNotExists("ROLE_USER");
        createRoleIfNotExists("ROLE_ADMIN");
    }

    private void createRoleIfNotExists(String name) {
        roleRepository.findByName(name).orElseGet(() -> roleRepository.save(Role.builder().name(name).build()));
    }
}
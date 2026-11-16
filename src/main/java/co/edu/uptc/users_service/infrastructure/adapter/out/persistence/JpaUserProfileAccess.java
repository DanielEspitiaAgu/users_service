package co.edu.uptc.users_service.infrastructure.adapter.out.persistence;

import co.edu.uptc.users_service.application.exception.ProfileStoreException;
import co.edu.uptc.users_service.domain.model.ProfileIdentityPolicy;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaUserProfileAccess {
    private final UserRepository userRepository;
    private final UserProfileCreator profileCreator;

    public User getOrCreate(String externalId) {
        ProfileIdentityPolicy.requireValid(externalId);
        try {
            Optional<User> existing = userRepository.findByExternalId(externalId);
            if (existing.isPresent()) {
                return existing.get();
            }
            try {
                return profileCreator.create(externalId);
            } catch (DataIntegrityViolationException concurrentCreate) {
                try {
                    return userRepository.findByExternalId(externalId)
                            .orElseThrow(() -> persistenceFailure(concurrentCreate));
                } catch (DataAccessException rereadFailure) {
                    throw persistenceFailure(rereadFailure);
                }
            }
        } catch (ProfileStoreException failure) {
            throw failure;
        } catch (DataAccessException failure) {
            throw persistenceFailure(failure);
        }
    }

    private ProfileStoreException persistenceFailure(Throwable cause) {
        return new ProfileStoreException("No fue posible acceder al perfil", cause);
    }
}

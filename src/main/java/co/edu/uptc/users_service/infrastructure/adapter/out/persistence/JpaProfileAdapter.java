package co.edu.uptc.users_service.infrastructure.adapter.out.persistence;

import co.edu.uptc.users_service.application.exception.ProfileStoreException;
import co.edu.uptc.users_service.application.port.out.ProfileStore;
import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileIdentityPolicy;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.repository.UserRepository;
import co.edu.uptc.users_service.service.UserProfileCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaProfileAdapter implements ProfileStore {
    private final UserRepository userRepository;
    private final UserProfileCreator profileCreator;

    @Override
    public PlayerProfile getOrCreate(String externalId) {
        return toDomain(getOrCreateEntity(externalId));
    }

    @Override
    public PlayerProfile update(String externalId, ProfileUpdate update) {
        try {
            User user = getOrCreateEntity(externalId);
            if (update.name() != null) user.setName(update.name());
            if (update.avatar() != null) user.setAvatar(update.avatar());
            return toDomain(userRepository.save(user));
        } catch (DataAccessException failure) {
            throw persistenceFailure(failure);
        }
    }

    /** Transitional entity bridge for language/tutorial and result services. */
    public User getOrCreateEntity(String externalId) {
        ProfileIdentityPolicy.requireValid(externalId);
        try {
            Optional<User> existing = userRepository.findByExternalId(externalId);
            if (existing.isPresent()) return existing.get();
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

    private PlayerProfile toDomain(User user) {
        return new PlayerProfile(user.getExternalId(), user.getUsername(), user.getEmail(),
                user.getName(), user.getAvatar(), user.getLanguage(), user.getTutorialCompleted(),
                user.getExperience(), user.getStreak(), user.getCreatedAt());
    }
}


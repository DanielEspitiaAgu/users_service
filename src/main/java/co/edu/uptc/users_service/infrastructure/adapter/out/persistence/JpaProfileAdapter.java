package co.edu.uptc.users_service.infrastructure.adapter.out.persistence;

import co.edu.uptc.users_service.application.exception.ProfileStoreException;
import co.edu.uptc.users_service.application.port.out.ProfileStore;
import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JpaProfileAdapter implements ProfileStore {
    private final JpaUserProfileAccess profileAccess;
    private final UserRepository userRepository;

    @Override
    public PlayerProfile getOrCreate(String externalId) {
        return toDomain(profileAccess.getOrCreate(externalId));
    }

    @Override
    public PlayerProfile update(String externalId, ProfileUpdate update) {
        try {
            User user = profileAccess.getOrCreate(externalId);
            if (update.name() != null) user.setName(update.name());
            if (update.avatar() != null) user.setAvatar(update.avatar());
            return toDomain(userRepository.save(user));
        } catch (DataAccessException failure) {
            throw persistenceFailure(failure);
        }
    }

    @Override
    @Transactional
    public PlayerProfile updateLanguage(String externalId, String language) {
        try {
            User user = profileAccess.getOrCreate(externalId);
            user.setLanguage(language);
            return toDomain(userRepository.save(user));
        } catch (DataAccessException failure) {
            throw persistenceFailure(failure);
        }
    }

    @Override
    @Transactional
    public PlayerProfile updateTutorial(String externalId, boolean completed) {
        try {
            User user = profileAccess.getOrCreate(externalId);
            user.setTutorialCompleted(completed);
            return toDomain(userRepository.save(user));
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


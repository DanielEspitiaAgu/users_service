package co.edu.uptc.users_service.application.usecase;

import co.edu.uptc.users_service.application.port.in.GetOrCreateProfileUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateLanguageUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateProfileUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateTutorialUseCase;
import co.edu.uptc.users_service.application.port.out.ProfileStore;
import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileIdentityPolicy;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;

public final class ProfileUseCase implements GetOrCreateProfileUseCase, UpdateProfileUseCase,
        UpdateLanguageUseCase, UpdateTutorialUseCase {
    private final ProfileStore profileStore;

    public ProfileUseCase(ProfileStore profileStore) {
        this.profileStore = profileStore;
    }

    @Override
    public PlayerProfile getOrCreate(String externalId) {
        ProfileIdentityPolicy.requireValid(externalId);
        return profileStore.getOrCreate(externalId);
    }

    @Override
    public PlayerProfile update(String externalId, ProfileUpdate update) {
        ProfileIdentityPolicy.requireValid(externalId);
        return profileStore.update(externalId, update);
    }

    @Override
    public PlayerProfile updateLanguage(String externalId, String language) {
        ProfileIdentityPolicy.requireValid(externalId);
        if (!"es".equals(language) && !"en".equals(language)) {
            throw new IllegalArgumentException("El idioma debe ser 'es' o 'en'");
        }
        return profileStore.updateLanguage(externalId, language);
    }

    @Override
    public PlayerProfile updateTutorial(String externalId, boolean completed) {
        ProfileIdentityPolicy.requireValid(externalId);
        return profileStore.updateTutorial(externalId, completed);
    }
}

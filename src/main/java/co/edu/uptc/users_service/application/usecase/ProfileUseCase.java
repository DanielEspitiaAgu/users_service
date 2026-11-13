package co.edu.uptc.users_service.application.usecase;

import co.edu.uptc.users_service.application.port.in.GetOrCreateProfileUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateProfileUseCase;
import co.edu.uptc.users_service.application.port.out.ProfileStore;
import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileIdentityPolicy;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;

public final class ProfileUseCase implements GetOrCreateProfileUseCase, UpdateProfileUseCase {
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
}

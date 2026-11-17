package co.edu.uptc.users_service.application.port.in;

import co.edu.uptc.users_service.domain.model.PlayerProfile;

public interface GetOrCreateProfileUseCase {
    PlayerProfile getOrCreate(String externalId);
}

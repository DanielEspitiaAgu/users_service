package co.edu.uptc.users_service.application.port.in;

import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;

public interface UpdateProfileUseCase {
    PlayerProfile update(String externalId, ProfileUpdate update);
}

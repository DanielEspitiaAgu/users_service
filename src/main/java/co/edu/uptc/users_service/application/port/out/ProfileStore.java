package co.edu.uptc.users_service.application.port.out;

import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;

public interface ProfileStore {
    PlayerProfile getOrCreate(String externalId);
    PlayerProfile update(String externalId, ProfileUpdate update);
}

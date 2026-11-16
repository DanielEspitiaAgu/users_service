package co.edu.uptc.users_service.application.port.in;

import co.edu.uptc.users_service.domain.model.PlayerProfile;

public interface UpdateLanguageUseCase {
    PlayerProfile updateLanguage(String externalId, String language);
}

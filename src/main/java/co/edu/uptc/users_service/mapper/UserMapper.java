package co.edu.uptc.users_service.mapper;

import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.dto.UserDTO;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class UserMapper {
    public UserDTO toDTO(PlayerProfile profile, Set<String> roles) {
        if (profile == null) return null;
        return new UserDTO(profile.externalId(), profile.username(), profile.email(), profile.name(), profile.avatar(),
                profile.language(), profile.tutorialCompleted(), profile.experience(), profile.streak(), roles, profile.createdAt());
    }
}

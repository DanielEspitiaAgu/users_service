package co.edu.uptc.users_service.mapper;

import co.edu.uptc.users_service.dto.UserDTO;
import co.edu.uptc.users_service.entity.User;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class UserMapper {

    public UserDTO toDTO(User user) {
        if (user == null)
            return null;

        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getName(),
                user.getAvatar(),
                user.getLanguage(),
                user.getTutorialCompleted(),
                user.getExperience(),
                user.getStreak(),
                user.getRoles().stream().map(r -> r.getName()).collect(Collectors.toSet()),
                user.getCreatedAt());
    }
}
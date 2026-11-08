package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.mapper.UserMapper;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final UserProfileService profileService;

    public UserDTO getOrCreate(String externalId, Set<String> roles) {
        return userMapper.toDTO(profileService.getOrCreate(externalId), roles);
    }

    public UserDTO updateProfile(String externalId, Set<String> roles, UpdateProfileDTO dto) {
        User user = profileService.getOrCreate(externalId);

        if (dto.name() != null) {
            user.setName(dto.name());
        }
        if (dto.avatar() != null) {
            user.setAvatar(dto.avatar());
        }

        return userMapper.toDTO(userRepository.save(user), roles);
    }

    public UserDTO updateLanguage(String externalId, Set<String> roles, LanguageDTO dto) {
        User user = profileService.getOrCreate(externalId);
        user.setLanguage(dto.language());
        return userMapper.toDTO(userRepository.save(user), roles);
    }

    public UserDTO updateTutorial(String externalId, Set<String> roles, TutorialDTO dto) {
        User user = profileService.getOrCreate(externalId);
        user.setTutorialCompleted(dto.completed());
        return userMapper.toDTO(userRepository.save(user), roles);
    }
}

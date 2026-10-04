package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.mapper.UserMapper;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Transactional
    public UserDTO updateProfile(String email, UpdateProfileDTO dto) {
        User user = findUserByEmail(email);

        if (dto.name() != null) {
            user.setName(dto.name());
        }
        if (dto.avatar() != null) {
            user.setAvatar(dto.avatar());
        }

        return userMapper.toDTO(userRepository.save(user));
    }

    @Transactional
    public UserDTO updateLanguage(String email, LanguageDTO dto) {
        User user = findUserByEmail(email);
        user.setLanguage(dto.language());
        return userMapper.toDTO(userRepository.save(user));
    }

    @Transactional
    public UserDTO updateTutorial(String email, TutorialDTO dto) {
        User user = findUserByEmail(email);
        user.setTutorialCompleted(dto.completed());
        return userMapper.toDTO(userRepository.save(user));
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }
}
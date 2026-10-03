package co.edu.uptc.users_service.service;

import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.uptc.users_service.dto.RegisterDTO;
import co.edu.uptc.users_service.dto.UserDTO;
import co.edu.uptc.users_service.entity.Role;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.mapper.UserMapper;
import co.edu.uptc.users_service.repository.RoleRepository;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    @Transactional
    public UserDTO register(RegisterDTO dto) {

        if (userRepository.existsByEmail(dto.email())) {
            throw new RuntimeException("El email ya está registrado");
        }
        if (userRepository.existsByUsername(dto.username())) {
            throw new RuntimeException("El username ya está en uso");
        }

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new RuntimeException("Rol ROLE_USER no encontrado"));

        User user = User.builder()
                .username(dto.username())
                .email(dto.email())
                .password(passwordEncoder.encode(dto.password()))
                .avatar(dto.avatar())
                .language("es")
                .tutorialCompleted(false)
                .experience(0)
                .streak(0)
                .roles(Set.of(userRole))
                .build();

        User saved = userRepository.save(user);
        return userMapper.toDTO(saved);
    }
}
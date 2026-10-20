package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.dto.*;
import co.edu.uptc.users_service.entity.Role;
import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.exception.ResourceNotFoundException;
import co.edu.uptc.users_service.mapper.UserMapper;
import co.edu.uptc.users_service.repository.RoleRepository;
import co.edu.uptc.users_service.repository.UserRepository;
import co.edu.uptc.users_service.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Value("${jwt.expiration}")
    private long expirationMs;

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

        return userMapper.toDTO(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public AuthResponseDTO login(LoginDTO dto) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.email(), dto.password()));

        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));

        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName().startsWith("ROLE_") ? r.getName() : "ROLE_" + r.getName())
                .sorted()
                .toList();

        String token = jwtService.generateToken(userDetails, user.getId(), roles);
        return new AuthResponseDTO(token, "Bearer", expirationMs / 1000);
    }

    @Transactional(readOnly = true)
    public UserDTO getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        return userMapper.toDTO(user);
    }
}
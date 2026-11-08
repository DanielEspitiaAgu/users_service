package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.exception.ResourceNotFoundException;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final UserRepository userRepository;
    private final UserProfileCreator profileCreator;

    public User getOrCreate(String externalId) {
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            throw new IllegalArgumentException("X-User-Id inválido");
        }

        return userRepository.findByExternalId(externalId).orElseGet(() -> {
            try {
                return profileCreator.create(externalId);
            } catch (DataIntegrityViolationException concurrentCreate) {
                return userRepository.findByExternalId(externalId)
                        .orElseThrow(() -> concurrentCreate);
            }
        });
    }
}

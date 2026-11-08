package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserProfileCreator {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User create(String externalId) {
        return userRepository.saveAndFlush(User.builder()
                .externalId(externalId)
                .language("es")
                .tutorialCompleted(false)
                .experience(0)
                .streak(0)
                .build());
    }
}

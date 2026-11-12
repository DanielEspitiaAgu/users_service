package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.infrastructure.adapter.out.persistence.JpaProfileAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserProfileService {
    private final JpaProfileAdapter profileAdapter;

    /** Transitional facade used by legacy language, tutorial, and result flows. */
    public User getOrCreate(String externalId) {
        return profileAdapter.getOrCreateEntity(externalId);
    }
}

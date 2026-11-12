package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.infrastructure.adapter.out.persistence.JpaProfileAdapter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {
    private final JpaProfileAdapter adapter = mock(JpaProfileAdapter.class);
    private final UserProfileService service = new UserProfileService(adapter);

    @Test
    void legacyCallDelegatesToSinglePersistenceCreationPath() {
        User profile = User.builder().id(1L).externalId("subject-1").build();
        when(adapter.getOrCreateEntity("subject-1")).thenReturn(profile);
        assertSame(profile, service.getOrCreate("subject-1"));
        verify(adapter).getOrCreateEntity("subject-1");
    }
}

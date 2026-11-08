package co.edu.uptc.users_service.service;

import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final UserProfileCreator creator = mock(UserProfileCreator.class);
    private final UserProfileService service = new UserProfileService(repository, creator);

    @Test
    void createsProfileWhenExternalIdIsNew() {
        User created = User.builder().id(1L).externalId("subject-1").build();
        when(repository.findByExternalId("subject-1")).thenReturn(Optional.empty());
        when(creator.create("subject-1")).thenReturn(created);

        assertSame(created, service.getOrCreate("subject-1"));
        verify(creator).create("subject-1");
    }

    @Test
    void returnsExistingProfileWithoutCreatingAnother() {
        User existing = User.builder().id(2L).externalId("subject-2").build();
        when(repository.findByExternalId("subject-2")).thenReturn(Optional.of(existing));

        assertSame(existing, service.getOrCreate("subject-2"));
        verifyNoInteractions(creator);
    }

    @Test
    void recoversConcurrentInsertByLoadingTheUniqueWinner() {
        User winner = User.builder().id(3L).externalId("subject-3").build();
        when(repository.findByExternalId("subject-3")).thenReturn(Optional.empty(), Optional.of(winner));
        when(creator.create("subject-3")).thenThrow(new DataIntegrityViolationException("unique external_id"));

        assertSame(winner, service.getOrCreate("subject-3"));
        verify(repository, times(2)).findByExternalId("subject-3");
    }

    @Test
    void rejectsInvalidExternalIdBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.getOrCreate("not/a-valid-id"));
        verifyNoInteractions(repository, creator);
    }
}

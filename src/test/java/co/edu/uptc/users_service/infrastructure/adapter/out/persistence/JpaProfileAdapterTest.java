package co.edu.uptc.users_service.infrastructure.adapter.out.persistence;

import co.edu.uptc.users_service.entity.User;
import co.edu.uptc.users_service.repository.UserRepository;
import co.edu.uptc.users_service.service.UserProfileCreator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaProfileAdapterTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final UserProfileCreator creator = mock(UserProfileCreator.class);
    private final JpaProfileAdapter adapter = new JpaProfileAdapter(repository, creator);

    @Test
    void createsAndMapsAProfileThroughTheIndependentTransactionCreator() {
        User created = User.builder().id(1L).externalId("subject-1").language("es").tutorialCompleted(false)
                .experience(0).streak(0).build();
        when(repository.findByExternalId("subject-1")).thenReturn(Optional.empty());
        when(creator.create("subject-1")).thenReturn(created);

        var profile = adapter.getOrCreate("subject-1");
        assertEquals("subject-1", profile.externalId());
        assertEquals("es", profile.language());
        assertEquals(false, profile.tutorialCompleted());
        verify(creator).create("subject-1");
    }

    @Test
    void returnsExistingProfileWithoutCreating() {
        User existing = User.builder().id(2L).externalId("subject-2").name("Name").build();
        when(repository.findByExternalId("subject-2")).thenReturn(Optional.of(existing));
        assertEquals("Name", adapter.getOrCreate("subject-2").name());
        verifyNoInteractions(creator);
    }

    @Test
    void recoversUniqueExternalIdRaceAfterIndependentTransactionRollsBack() {
        User winner = User.builder().id(3L).externalId("subject-3").name("Winner").build();
        when(repository.findByExternalId("subject-3")).thenReturn(Optional.empty(), Optional.of(winner));
        when(creator.create("subject-3")).thenThrow(new DataIntegrityViolationException("unique external_id"));
        assertEquals("Winner", adapter.getOrCreate("subject-3").name());
        verify(repository, times(2)).findByExternalId("subject-3");
    }

    @Test
    void nullUpdateFieldsDoNotOverwriteExistingValues() {
        User existing = User.builder().id(4L).externalId("subject-4").name("Keep").avatar("keep.png").build();
        when(repository.findByExternalId("subject-4")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        var updated = adapter.update("subject-4", new co.edu.uptc.users_service.domain.model.ProfileUpdate(null, "new.png"));
        assertEquals("Keep", updated.name());
        assertEquals("new.png", updated.avatar());
    }
}

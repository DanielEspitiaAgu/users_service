package co.edu.uptc.users_service.application.usecase;

import co.edu.uptc.users_service.application.port.out.ProfileStore;
import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProfileUseCaseTest {
    @Test
    void delegatesGetAndUpdateToOutputPortAndPreservesNullPatchValues() {
        PlayerProfile profile = new PlayerProfile("subject-1", null, null, "Name", null,
                "es", false, 0, 0, null);
        AtomicReference<ProfileUpdate> patch = new AtomicReference<>();
        ProfileStore fake = new ProfileStore() {
            public PlayerProfile getOrCreate(String id) { return profile; }
            public PlayerProfile update(String id, ProfileUpdate update) { patch.set(update); return profile; }
        };
        ProfileUseCase useCase = new ProfileUseCase(fake);

        assertSame(profile, useCase.getOrCreate("subject-1"));
        assertSame(profile, useCase.update("subject-1", new ProfileUpdate(null, "avatar")));
        assertEquals(new ProfileUpdate(null, "avatar"), patch.get());
        assertThrows(IllegalArgumentException.class, () -> useCase.getOrCreate("bad/id"));
    }
}


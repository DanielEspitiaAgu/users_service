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
            public PlayerProfile updateLanguage(String id, String language) { return profile; }
            public PlayerProfile updateTutorial(String id, boolean completed) { return profile; }
        };
        ProfileUseCase useCase = new ProfileUseCase(fake);

        assertSame(profile, useCase.getOrCreate("subject-1"));
        assertSame(profile, useCase.update("subject-1", new ProfileUpdate(null, "avatar")));
        assertEquals(new ProfileUpdate(null, "avatar"), patch.get());
        assertThrows(IllegalArgumentException.class, () -> useCase.getOrCreate("bad/id"));
    }

    @Test
    void validatesPreferenceUpdatesBeforeCallingTheOutputPort() {
        AtomicReference<String> language = new AtomicReference<>();
        ProfileStore fake = new ProfileStore() {
            public PlayerProfile getOrCreate(String id) { return null; }
            public PlayerProfile update(String id, ProfileUpdate update) { return null; }
            public PlayerProfile updateLanguage(String id, String value) {
                language.set(value);
                return new PlayerProfile(id, null, null, null, null, value, false, 0, 0, null);
            }
            public PlayerProfile updateTutorial(String id, boolean completed) {
                return new PlayerProfile(id, null, null, null, null, "es", completed, 0, 0, null);
            }
        };
        ProfileUseCase useCase = new ProfileUseCase(fake);

        assertThrows(IllegalArgumentException.class, () -> useCase.updateLanguage("subject-1", "fr"));
        assertNull(language.get());
        assertEquals("en", useCase.updateLanguage("subject-1", "en").language());
        assertEquals("en", language.get());
        assertTrue(useCase.updateTutorial("subject-1", true).tutorialCompleted());
        assertThrows(IllegalArgumentException.class, () -> useCase.updateTutorial("bad/id", false));
    }
}


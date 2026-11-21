package co.edu.uptc.users_service.controller;

import co.edu.uptc.users_service.config.SecurityConfig;
import co.edu.uptc.users_service.application.port.in.GetOrCreateProfileUseCase;
import co.edu.uptc.users_service.application.port.in.StatsUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateLanguageUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateProfileUseCase;
import co.edu.uptc.users_service.application.port.in.UpdateTutorialUseCase;
import co.edu.uptc.users_service.application.exception.ProfileStoreException;
import co.edu.uptc.users_service.domain.model.PlayerProfile;
import co.edu.uptc.users_service.domain.model.ProfileUpdate;
import co.edu.uptc.users_service.security.GatewayIdentityFilter;
import co.edu.uptc.users_service.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, GatewayIdentityFilter.class, UserMapper.class})
@TestPropertySource(properties = {
        "security.gateway-secret=test-gateway-secret",
        "internal.api-token=test-internal-token",
        "METRICS_SCRAPE_USERNAME=metrics",
        "METRICS_SCRAPE_PASSWORD=test-scrape-password"
})
class UserProfileHttpCharacterizationTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private StatsUseCase statsUseCase;
    @MockitoBean private GetOrCreateProfileUseCase getOrCreateProfile;
    @MockitoBean private UpdateProfileUseCase updateProfile;
    @MockitoBean private UpdateLanguageUseCase updateLanguage;
    @MockitoBean private UpdateTutorialUseCase updateTutorial;

    @Test
    void getMeReturnsNewProfileContractAndUsesAuthenticatedSubjectAndRoles() throws Exception {
        when(getOrCreateProfile.getOrCreate("subject-new")).thenReturn(profile("subject-new", null, null));
        mockMvc.perform(get("/users/me").headers(gatewayHeaders("subject-new", "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("subject-new"))
                .andExpect(jsonPath("$.name").doesNotExist()).andExpect(jsonPath("$.roles[0]").value("USER"));
        verify(getOrCreateProfile).getOrCreate("subject-new");
    }

    @Test
    void getMeReturnsExistingProfileContract() throws Exception {
        when(getOrCreateProfile.getOrCreate("subject-existing")).thenReturn(profile("subject-existing", "Detective", "avatar.png"));
        mockMvc.perform(get("/users/me").headers(gatewayHeaders("subject-existing", "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Detective"))
                .andExpect(jsonPath("$.avatar").value("avatar.png")).andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void publicDisplayNameIsRestrictedToInternalServicesAndExposesNoProfileFields() throws Exception {
        when(getOrCreateProfile.getOrCreate("subject-existing"))
                .thenReturn(profile("subject-existing", "Detective", "avatar.png"));
        mockMvc.perform(get("/users/subject-existing/display-name")
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Detective"))
                .andExpect(jsonPath("$.avatar").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());

        mockMvc.perform(get("/users/subject-existing/display-name")
                        .headers(gatewayHeaders("subject-existing", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicDisplayNameUsesNeutralFallbackInsteadOfExposingUsernameWhenProfileNameIsBlank() throws Exception {
        when(getOrCreateProfile.getOrCreate("subject-existing"))
                .thenReturn(new PlayerProfile("subject-existing", "private-username", "private@example.test",
                        "  ", "avatar.png", "es", false, 0, 0, null));
        mockMvc.perform(get("/users/subject-existing/display-name")
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Detective"))
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.avatar").doesNotExist());
    }

    @Test
    void protectedProfileRouteRejectsMissingOrUntrustedGatewayIdentity() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(get("/users/me").header("X-User-Id", "spoofed").header("X-Roles", "ADMIN"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/users/me").headers(gatewayHeaders("subject-1", "offline_access")))
                .andExpect(status().isForbidden());
    }

    @Test
    void putAllowsOptionalNullFieldsAndDoesNotTakeIdentityOrRolesFromBody() throws Exception {
        when(updateProfile.update(eq("subject-existing"), eq(new ProfileUpdate(null, "new-avatar.png"))))
                .thenReturn(profile("subject-existing", "Existing name", "new-avatar.png"));
        mockMvc.perform(put("/users/me").headers(gatewayHeaders("subject-existing", "USER"))
                        .contentType("application/json")
                        .content("{\"name\":null,\"avatar\":\"new-avatar.png\",\"userId\":\"attacker\",\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("subject-existing"))
                .andExpect(jsonPath("$.name").value("Existing name")).andExpect(jsonPath("$.avatar").value("new-avatar.png"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
        verify(updateProfile).update("subject-existing", new ProfileUpdate(null, "new-avatar.png"));
    }

    @Test
    void putRejectsValuesOverExistingValidationLimits() throws Exception {
        mockMvc.perform(put("/users/me").headers(gatewayHeaders("subject-existing", "USER"))
                        .contentType("application/json").content("{\"name\":\"" + "x".repeat(101) + "\",\"avatar\":null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.errors[0].field").value("name"));
    }

    @Test
    void blankNameRemainsAcceptedByTheExistingContract() throws Exception {
        when(updateProfile.update(eq("subject-existing"), eq(new ProfileUpdate("   ", null))))
                .thenReturn(profile("subject-existing", "   ", null));
        mockMvc.perform(put("/users/me").headers(gatewayHeaders("subject-existing", "USER"))
                        .contentType("application/json").content("{\"name\":\"   \",\"avatar\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("   "));
    }

    @Test
    void putRejectsAvatarOverExistingValidationLimit() throws Exception {
        mockMvc.perform(put("/users/me").headers(gatewayHeaders("subject-existing", "USER"))
                        .contentType("application/json").content("{\"name\":null,\"avatar\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void languageAndTutorialUpdatesUseTheAuthenticatedSubjectUseCases() throws Exception {
        when(updateLanguage.updateLanguage("subject-existing", "en"))
                .thenReturn(new PlayerProfile("subject-existing", null, null, null, null,
                        "en", false, 0, 0, null));
        when(updateTutorial.updateTutorial("subject-existing", true))
                .thenReturn(new PlayerProfile("subject-existing", null, null, null, null,
                        "en", true, 0, 0, null));

        mockMvc.perform(MockMvcRequestBuilders.patch("/users/me/language")
                        .headers(gatewayHeaders("subject-existing", "USER"))
                        .contentType("application/json").content("{\"language\":\"en\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.language").value("en"));
        mockMvc.perform(MockMvcRequestBuilders.put("/users/me/tutorial")
                        .headers(gatewayHeaders("subject-existing", "USER"))
                        .contentType("application/json").content("{\"completed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tutorialCompleted").value(true));

        verify(updateLanguage).updateLanguage("subject-existing", "en");
        verify(updateTutorial).updateTutorial("subject-existing", true);
    }

    @Test
    void profilePersistenceFailuresAreReportedAsUnavailable() throws Exception {
        when(getOrCreateProfile.getOrCreate("subject-existing"))
                .thenThrow(new ProfileStoreException("database unavailable", new IllegalStateException()));

        mockMvc.perform(get("/users/me").headers(gatewayHeaders("subject-existing", "USER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("UPSTREAM_UNAVAILABLE"));
    }

    private static PlayerProfile profile(String subject, String name, String avatar) {
        return new PlayerProfile(subject, null, null, name, avatar, "es", false, 0, 0, null);
    }

    private static org.springframework.http.HttpHeaders gatewayHeaders(String subject, String roles) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Gateway-Secret", "test-gateway-secret");
        headers.set("X-User-Id", subject);
        headers.set("X-Roles", roles);
        return headers;
    }
}








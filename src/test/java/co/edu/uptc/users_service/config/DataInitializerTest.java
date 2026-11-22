package co.edu.uptc.users_service.config;

import co.edu.uptc.users_service.entity.Achievement;
import co.edu.uptc.users_service.repository.AchievementRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    @Test
    void initializesAtLeastTenEventEvaluatedAchievementsIdempotentlyByCode() {
        AchievementRepository repository = mock(AchievementRepository.class);
        Map<String, Achievement> stored = new HashMap<>();
        when(repository.findByCode(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(repository.save(any(Achievement.class))).thenAnswer(invocation -> {
            Achievement achievement = invocation.getArgument(0);
            stored.put(achievement.getCode(), achievement);
            return achievement;
        });

        new DataInitializer(repository).run();
        new DataInitializer(repository).run();

        ArgumentCaptor<Achievement> captor = ArgumentCaptor.forClass(Achievement.class);
        verify(repository, times(11)).save(captor.capture());
        var achievements = captor.getAllValues();
        assertTrue(achievements.stream().map(Achievement::getCode).distinct().count() >= 10);
        assertTrue(achievements.stream().allMatch(item ->
                item.getRequirement().matches("(WINS|XP|STREAK)>=[0-9]+")));
        verify(repository, times(22)).findByCode(any());
    }
}

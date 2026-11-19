package co.edu.uptc.users_service.application.usecase;

import co.edu.uptc.users_service.application.port.out.StatsStore;
import co.edu.uptc.users_service.domain.model.StatsPlayer;
import co.edu.uptc.users_service.domain.model.StoredResult;
import co.edu.uptc.users_service.domain.service.ProgressionPolicy;
import co.edu.uptc.users_service.dto.ResultDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class StatsUseCaseImplTest {
    private final StatsStore statsStore = mock(StatsStore.class);
    private final StatsUseCaseImpl useCase = new StatsUseCaseImpl(statsStore, new ProgressionPolicy());

    @Test
    void rejectsInvalidResultBeforeAccessingPersistence() {
        ResultDTO invalid = new ResultDTO("match-1", "case-1", "TIMED", true, 30, null, null);

        assertThrows(IllegalArgumentException.class, () -> useCase.registerResult("subject-1", invalid));

        verify(statsStore, never()).getOrCreateAndLockPlayer("subject-1");
    }

    @Test
    void repeatedMatchReturnsThePersistedResultWithoutApplyingRewardsAgain() {
        StatsPlayer player = new StatsPlayer(7L, 50, 2);
        StoredResult persisted = new StoredResult(
                11L, "match-1", "case-1", "CLASSIC", "EASY", true, 30, 50, true);
        ResultDTO retry = new ResultDTO("match-1", "case-1", "CLASSIC", true, 30, "EASY", null);
        when(statsStore.getOrCreateAndLockPlayer("subject-1")).thenReturn(player);
        when(statsStore.findResultByUserIdAndMatchId(7L, "match-1")).thenReturn(Optional.of(persisted));
        when(statsStore.findResultsByUserId(7L)).thenReturn(List.of(persisted));
        when(statsStore.countUniqueCasesSolvedByUserId(7L)).thenReturn(1L);

        var response = useCase.registerResult("subject-1", retry);

        assertEquals("match-1", response.matchId());
        assertEquals(50, response.xpDelta());
        assertEquals(1, response.stats().solvedCases());
        assertTrue(response.newAchievements().isEmpty());
        verify(statsStore, never()).insertResultIfAbsent(eq(7L), any());
        verify(statsStore, never()).updatePlayerProgress(anyLong(), anyInt(), anyInt());
    }

    @Test
    void solvedCasesIncludeOnlyIdsReturnedByTheStatsStore() {
        when(statsStore.getOrCreatePlayer("subject-1"))
                .thenReturn(new StatsPlayer(7L, 200, 2));
        when(statsStore.findSolvedCaseIdsByUserId(7L))
                .thenReturn(List.of("case-a", "case-b"));

        assertEquals(List.of("case-a", "case-b"), useCase.getSolvedCaseIds("subject-1"));
    }
}

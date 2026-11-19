package co.edu.uptc.users_service.application.port.in;

import co.edu.uptc.users_service.dto.AchievementDTO;
import co.edu.uptc.users_service.dto.ProgressionDTO;
import co.edu.uptc.users_service.dto.ResultDTO;
import co.edu.uptc.users_service.dto.ResultResponseDTO;
import co.edu.uptc.users_service.dto.StatsDTO;

import java.util.List;

public interface StatsUseCase {
    StatsDTO getStats(String externalId);

    ProgressionDTO getProgression(String externalId);

    List<String> getSolvedCaseIds(String externalId);

    List<AchievementDTO> getAchievements(String externalId);

    ResultResponseDTO registerResult(String externalId, ResultDTO result);
}

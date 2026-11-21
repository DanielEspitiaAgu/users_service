package co.edu.uptc.users_service.domain.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressionPolicyTest {
    private final ProgressionPolicy policy = new ProgressionPolicy();

    @ParameterizedTest
    @CsvSource({
            "-10,1", "0,1", "49,1", "50,2", "99,2", "100,3", "149,3", "150,4",
            "199,4", "200,5", "299,5", "300,6", "424,6", "425,7", "574,7",
            "575,8", "749,8", "750,9", "949,9", "950,10", "1199,10", "1200,11",
            "1499,11", "1500,12", "1849,12", "1850,13", "2249,13", "2250,14",
            "2699,14", "2700,15", "3199,15", "3200,16", "3749,16", "3750,17",
            "4349,17", "4350,18", "4999,18", "5000,19", "5699,19", "5700,20"
    })
    void derivesLevelAtEachExperienceBoundary(int experience, int expectedLevel) {
        assertEquals(expectedLevel, policy.levelForExperience(experience));
    }

    @Test
    void fourEasyCaseRewardsReachLevelFiveThreshold() {
        assertEquals(5, policy.levelForExperience(4 * 50));
    }

    @ParameterizedTest
    @CsvSource({"EASY,1", "MEDIUM,5", "HARD,10"})
    void mapsDifficultyToRequiredLevel(String difficulty, int expectedLevel) {
        assertEquals(expectedLevel, policy.requiredLevel(difficulty));
    }
}

package com.learne.backend.shared.dashboard.service;

import com.learne.backend.cache.CacheNames;
import com.learne.backend.shared.dashboard.dto.LeaderboardStudentResponse;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class LeaderboardReadCacheService {
    private final LeaderboardService leaderboardService;

    public LeaderboardReadCacheService(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @Cacheable(cacheNames = CacheNames.LEADERBOARD,
            key = "T(com.learne.backend.cache.CacheKeySupport).leaderboard(#limit)")
    public List<LeaderboardStudentResponse> topStudents(int limit) {
        return leaderboardService.topStudents(limit);
    }
}

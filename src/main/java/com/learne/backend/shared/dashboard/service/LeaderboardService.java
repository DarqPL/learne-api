package com.learne.backend.shared.dashboard.service;

import com.learne.backend.shared.dashboard.dto.LeaderboardStudentResponse;
import com.learne.backend.student.learning.entity.UserStats;
import com.learne.backend.student.learning.repository.UserStatsRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaderboardService {

    private final UserStatsRepository userStatsRepository;

    public LeaderboardService(UserStatsRepository userStatsRepository) {
        this.userStatsRepository = userStatsRepository;
    }

    @Transactional(readOnly = true)
    public List<LeaderboardStudentResponse> topStudents(int limit) {
        return userStatsRepository.findTopStudentStats(PageRequest.of(0, limit)).stream()
                .map(this::toResponse)
                .toList();
    }

    private LeaderboardStudentResponse toResponse(UserStats stats) {
        return new LeaderboardStudentResponse(
                stats.getUser().getId(),
                stats.getUser().getFullName(),
                stats.getTotalXp(),
                stats.getCurrentStreakDays(),
                stats.getLongestStreakDays(),
                stats.getLastActivityDate());
    }
}

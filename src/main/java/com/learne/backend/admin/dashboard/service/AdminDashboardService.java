package com.learne.backend.admin.dashboard.service;

import com.learne.backend.admin.dashboard.dto.AdminDashboardOverviewResponse;
import com.learne.backend.admin.dashboard.dto.AdminDashboardResponse;
import com.learne.backend.admin.dashboard.repository.AdminDashboardOverviewRow;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.shared.dashboard.dto.StatusCountResponse;
import com.learne.backend.shared.dashboard.service.LeaderboardReadCacheService;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.util.Arrays;
import java.util.EnumMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final LeaderboardReadCacheService leaderboardReadCacheService;

    public AdminDashboardService(
            UserRepository userRepository,
            CourseRepository courseRepository,
            LeaderboardReadCacheService leaderboardReadCacheService) {
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.leaderboardReadCacheService = leaderboardReadCacheService;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        AdminDashboardOverviewRow overviewRow = userRepository.adminDashboardOverview();
        AdminDashboardOverviewResponse overview = new AdminDashboardOverviewResponse(
                overviewRow.getTotalStudents(),
                overviewRow.getLockedStudents(),
                overviewRow.getTotalTeachers(),
                overviewRow.getLockedTeachers(),
                overviewRow.getTotalCourses(),
                overviewRow.getTotalEnrollments());
        EnumMap<ContentStatus, Long> statusCounts = new EnumMap<>(ContentStatus.class);
        courseRepository.countByStatusGrouped()
                .forEach(row -> statusCounts.put(row.getStatus(), row.getTotal()));

        return new AdminDashboardResponse(
                overview,
                Arrays.stream(ContentStatus.values())
                        .map(status -> new StatusCountResponse(status.name(), statusCounts.getOrDefault(status, 0L)))
                        .toList(),
                leaderboardReadCacheService.topStudents(5));
    }
}

package com.learne.backend.admin.dashboard.service;

import com.learne.backend.admin.dashboard.dto.AdminDashboardOverviewResponse;
import com.learne.backend.admin.dashboard.dto.AdminDashboardResponse;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.dashboard.dto.StatusCountResponse;
import com.learne.backend.shared.dashboard.service.LeaderboardService;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final LeaderboardService leaderboardService;

    public AdminDashboardService(
            UserRepository userRepository,
            CourseRepository courseRepository,
            CourseEnrollmentRepository courseEnrollmentRepository,
            LeaderboardService leaderboardService) {
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.leaderboardService = leaderboardService;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        AdminDashboardOverviewResponse overview = new AdminDashboardOverviewResponse(
                userRepository.countByRole(UserRole.STUDENT),
                userRepository.countByRoleAndLocked(UserRole.STUDENT, true),
                userRepository.countByRole(UserRole.TEACHER),
                userRepository.countByRoleAndLocked(UserRole.TEACHER, true),
                courseRepository.count(),
                courseEnrollmentRepository.count());

        return new AdminDashboardResponse(
                overview,
                Arrays.stream(ContentStatus.values())
                        .map(status -> new StatusCountResponse(status.name(), courseRepository.countByStatus(status)))
                        .toList(),
                leaderboardService.topStudents(5));
    }
}

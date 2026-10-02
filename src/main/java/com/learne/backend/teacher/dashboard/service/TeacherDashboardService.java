package com.learne.backend.teacher.dashboard.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.dashboard.dto.StatusCountResponse;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.dashboard.dto.TeacherDashboardOverviewResponse;
import com.learne.backend.teacher.dashboard.dto.TeacherDashboardResponse;
import com.learne.backend.teacher.dashboard.dto.TeacherRecentCourseResponse;
import com.learne.backend.teacher.dashboard.repository.TeacherDashboardOverviewRow;
import java.util.Arrays;
import java.util.EnumMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherDashboardService {
    private final CourseRepository courseRepository;

    public TeacherDashboardService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Transactional(readOnly = true)
    public TeacherDashboardResponse getDashboard(User teacher) {
        TeacherDashboardOverviewRow overviewRow = courseRepository.teacherDashboardOverview(teacher);
        TeacherDashboardOverviewResponse overview = new TeacherDashboardOverviewResponse(
                overviewRow.getTotalCourses(),
                overviewRow.getTotalStudents(),
                overviewRow.getTotalLessons(),
                overviewRow.getTotalQuestions()
        );
        EnumMap<ContentStatus, Long> statusCounts = new EnumMap<>(ContentStatus.class);
        courseRepository.countByTeacherAndStatusGrouped(teacher)
                .forEach(row -> statusCounts.put(row.getStatus(), row.getTotal()));

        return new TeacherDashboardResponse(
                overview,
                Arrays.stream(ContentStatus.values())
                        .map(status -> new StatusCountResponse(
                                status.name(),
                                statusCounts.getOrDefault(status, 0L)))
                        .toList(),
                courseRepository.findTop5ByTeacherOrderByUpdatedAtDescIdDesc(teacher).stream()
                        .map(TeacherRecentCourseResponse::from)
                        .toList()
        );
    }
}

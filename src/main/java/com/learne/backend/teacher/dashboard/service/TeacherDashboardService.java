package com.learne.backend.teacher.dashboard.service;

import com.learne.backend.domain.auth.User;
import com.learne.backend.shared.dashboard.dto.StatusCountResponse;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import com.learne.backend.teacher.dashboard.dto.TeacherDashboardOverviewResponse;
import com.learne.backend.teacher.dashboard.dto.TeacherDashboardResponse;
import com.learne.backend.teacher.dashboard.dto.TeacherRecentCourseResponse;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherDashboardService {
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final LessonRepository lessonRepository;
    private final QuestionRepository questionRepository;

    public TeacherDashboardService(CourseRepository courseRepository,
                                   CourseEnrollmentRepository courseEnrollmentRepository,
                                   LessonRepository lessonRepository,
                                   QuestionRepository questionRepository) {
        this.courseRepository = courseRepository;
        this.courseEnrollmentRepository = courseEnrollmentRepository;
        this.lessonRepository = lessonRepository;
        this.questionRepository = questionRepository;
    }

    @Transactional(readOnly = true)
    public TeacherDashboardResponse getDashboard(User teacher) {
        TeacherDashboardOverviewResponse overview = new TeacherDashboardOverviewResponse(
                courseRepository.countByTeacher(teacher),
                courseEnrollmentRepository.countDistinctStudentsByTeacher(teacher),
                lessonRepository.countByCourseTeacher(teacher),
                questionRepository.countByCourseTeacher(teacher)
        );

        return new TeacherDashboardResponse(
                overview,
                Arrays.stream(ContentStatus.values())
                        .map(status -> new StatusCountResponse(
                                status.name(),
                                courseRepository.countByTeacherAndStatus(teacher, status)))
                        .toList(),
                courseRepository.findTop5ByTeacherOrderByUpdatedAtDescIdDesc(teacher).stream()
                        .map(TeacherRecentCourseResponse::from)
                        .toList()
        );
    }
}

package com.learne.backend.student.learning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.User;
import com.learne.backend.monetization.service.CourseAccessService;
import com.learne.backend.shared.dashboard.service.LeaderboardReadCacheService;
import com.learne.backend.shared.pagination.CursorTokenService;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.LearningActivityRepository;
import com.learne.backend.student.learning.repository.LessonProgressRepository;
import com.learne.backend.student.learning.repository.QuestionAnswerRepository;
import com.learne.backend.student.learning.repository.QuizAttemptRepository;
import com.learne.backend.student.learning.service.CourseAccessContextService;
import com.learne.backend.student.learning.service.StudentLearningService;
import com.learne.backend.student.learning.service.StudentQuizGradingService;
import com.learne.backend.student.learning.service.StudentStatsService;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.QuestionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

class StudentLearningServiceTests {
    @Test
    void listCoursesUsesEmptyStringSearchParameterWhenQueryIsAbsent() {
        CourseRepository courseRepository = mock(CourseRepository.class);
        when(courseRepository.findStudentCatalogRows(
                any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        StudentLearningService service = new StudentLearningService(
                courseRepository,
                mock(CourseEnrollmentRepository.class),
                mock(LessonProgressRepository.class),
                mock(QuestionRepository.class),
                mock(QuizAttemptRepository.class),
                mock(QuestionAnswerRepository.class),
                mock(LearningActivityRepository.class),
                mock(StudentQuizGradingService.class),
                mock(StudentStatsService.class),
                mock(LeaderboardReadCacheService.class),
                mock(CourseAccessService.class),
                mock(CourseAccessContextService.class),
                mock(CacheInvalidationService.class),
                mock(EntityManager.class),
                mock(CursorTokenService.class),
                mock(PlatformTransactionManager.class));

        service.listCourses(new User(), null, null, null, 6, null);

        verify(courseRepository).findStudentCatalogRows(
                eq(ContentStatus.APPROVED),
                eq(null),
                eq(null),
                eq(""),
                notNull(Instant.class),
                notNull(Long.class),
                any(Pageable.class));
    }
}

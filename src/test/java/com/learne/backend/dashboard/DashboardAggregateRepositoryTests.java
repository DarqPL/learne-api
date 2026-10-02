package com.learne.backend.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.dashboard.repository.TeacherDashboardOverviewRow;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class DashboardAggregateRepositoryTests {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CourseRepository courseRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
        insertUsers();
        insertCoursesAndLearningContent();
    }

    @Test
    void teacherDashboardOverviewCountsTeacherScopedRows() {
        User teacher = userRepository.findByEmail("teacher@example.com").orElseThrow();

        TeacherDashboardOverviewRow row = courseRepository.teacherDashboardOverview(teacher);

        assertThat(row.getTotalCourses()).isEqualTo(2);
        assertThat(row.getTotalStudents()).isEqualTo(1);
        assertThat(row.getTotalLessons()).isEqualTo(2);
        assertThat(row.getTotalQuestions()).isEqualTo(3);
    }

    @Test
    void courseStatusCountsAreGroupedInSingleResultSet() {
        Map<ContentStatus, Long> counts = courseRepository.countByStatusGrouped().stream()
                .collect(Collectors.toMap(CourseRepository.CourseStatusCountRow::getStatus,
                        CourseRepository.CourseStatusCountRow::getTotal));

        assertThat(counts.get(ContentStatus.APPROVED)).isEqualTo(1);
        assertThat(counts.get(ContentStatus.DRAFT)).isEqualTo(1);
        assertThat(counts.get(ContentStatus.PENDING_REVIEW)).isEqualTo(1);
    }

    private void insertUsers() {
        jdbcTemplate.update("""
                insert into users(id, email, password_hash, full_name, role, is_active, is_locked, must_change_password)
                values
                    (101, 'teacher@example.com', 'hash', 'Teacher User', 'TEACHER', true, false, false),
                    (102, 'other-teacher@example.com', 'hash', 'Other Teacher', 'TEACHER', true, false, false),
                    (201, 'student@example.com', 'hash', 'Student User', 'STUDENT', true, false, false)
                """);
    }

    private void insertCoursesAndLearningContent() {
        jdbcTemplate.update("""
                insert into courses(id, teacher_id, name, description, type, level, order_index, status, published_at)
                values
                    (301, 101, 'Approved Course', 'A', 'GENERAL', 'A1', 1, 'APPROVED', CURRENT_TIMESTAMP),
                    (302, 101, 'Draft Course', 'D', 'GENERAL', 'A2', 2, 'DRAFT', null),
                    (303, 102, 'Review Course', 'R', 'GENERAL', 'B1', 3, 'PENDING_REVIEW', null)
                """);
        jdbcTemplate.update("""
                insert into topics(id, course_id, name, description, order_index)
                values (401, 301, 'Topic A', 'A', 1), (402, 302, 'Topic D', 'D', 1)
                """);
        jdbcTemplate.update("""
                insert into lessons(id, topic_id, teacher_id, title, description, content, skill_type, order_index, xp_reward, status)
                values
                    (501, 401, 101, 'Lesson A1', 'A1', 'Content', 'READING', 1, 10, 'APPROVED'),
                    (502, 402, 101, 'Lesson D1', 'D1', 'Content', 'READING', 1, 10, 'DRAFT')
                """);
        jdbcTemplate.update("""
                insert into questions(id, lesson_id, teacher_id, type, question_text, order_index, xp_reward, status)
                values
                    (601, 501, 101, 'MULTIPLE_CHOICE', 'Q1', 1, 1, 'APPROVED'),
                    (602, 501, 101, 'TRUE_FALSE', 'Q2', 2, 1, 'APPROVED'),
                    (603, 502, 101, 'SHORT_ANSWER', 'Q3', 1, 1, 'DRAFT')
                """);
        jdbcTemplate.update("""
                insert into course_enrollments(id, user_id, course_id, status, progress_percent)
                values (701, 201, 301, 'ACTIVE', 0)
                """);
    }
}

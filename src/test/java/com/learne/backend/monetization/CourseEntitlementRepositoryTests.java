package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.monetization.entity.CourseEntitlement;
import com.learne.backend.monetization.enums.CourseEntitlementSource;
import com.learne.backend.monetization.repository.CourseEntitlementRepository;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class CourseEntitlementRepositoryTests {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private CourseEntitlementRepository courseEntitlementRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void coursePurchaseEntitlementCanBePersistedAndFound() {
        User teacher = userRepository.save(user("teacher@example.com", "Teacher", UserRole.TEACHER));
        User student = userRepository.save(user("student@example.com", "Student", UserRole.STUDENT));
        Course course = courseRepository.save(course(teacher));

        CourseEntitlement entitlement = new CourseEntitlement();
        entitlement.setUser(student);
        entitlement.setCourse(course);
        entitlement.setSource(CourseEntitlementSource.COURSE_PURCHASE);
        entitlement.setStartsAt(Instant.now().minusSeconds(60));
        courseEntitlementRepository.save(entitlement);

        assertThat(courseEntitlementRepository.existsActiveCourseAccess(student, course, Instant.now())).isTrue();
    }

    private User user(String email, String fullName, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return user;
    }

    private Course course(User teacher) {
        Course course = new Course();
        course.setTeacher(teacher);
        course.setName("Paid Course");
        course.setDescription("Paid course description");
        course.setType(CourseType.GRAMMAR);
        course.setLevel("A1");
        course.setOrderIndex(1);
        course.setStatus(ContentStatus.APPROVED);
        course.setPublishedAt(Instant.now());
        return course;
    }
}

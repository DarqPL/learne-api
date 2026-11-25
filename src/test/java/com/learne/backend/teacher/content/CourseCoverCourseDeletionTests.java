package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.teacher.content.service.TeacherContentService;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.learne.backend.teacher.content.audio.service.MediaCleanupProducer;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.jobs.worker-enabled=false")
class CourseCoverCourseDeletionTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private TeacherContentService content;
    @MockitoBean private MediaCleanupProducer cleanup;

    @BeforeEach
    void cleanDatabase() {
        TestDatabaseCleaner.clean(jdbc);
    }

    @Test
    void deletingCourseQueuesPublicRequestAndRevisionCoverMedia() throws Exception {
        User teacher = createTeacher();
        jdbc.update("""
                insert into courses(teacher_id,name,type,level,status,image_url,image_public_id)
                values (?,'Delete Covers','GRAMMAR','A1','DRAFT',
                    'https://example.test/public.jpg','learne/course-covers/public')
                """, teacher.getId());
        long courseId = jdbc.queryForObject(
                "select id from courses where name='Delete Covers'", Long.class);

        jdbc.update("""
                insert into course_cover_reviews(
                    course_id,teacher_id,image_url,image_public_id,status)
                values (?,?,'https://example.test/request.jpg','learne/course-covers/request','PENDING_REVIEW')
                """, courseId, teacher.getId());
        jdbc.update("""
                insert into course_pending_review_snapshots(course_id,snapshot_kind,cover_review_id)
                values (?,'COVER_ONLY',
                    (select id from course_cover_reviews where course_id=?))
                """, courseId, courseId);
        insertRevision(courseId, teacher.getId(), 1, "DRAFT", "draft");
        insertRevision(courseId, teacher.getId(), 2, "REJECTED", "rejected");

        content.deleteCourse(teacher, courseId);

        verify(cleanup).enqueueCourseCover(any(), eq(courseId),
                eq("https://example.test/public.jpg"), eq("learne/course-covers/public"));
        verify(cleanup).enqueueCourseCover(any(), eq(courseId),
                eq("https://example.test/request.jpg"), eq("learne/course-covers/request"));
        verify(cleanup).enqueueCourseCover(any(), eq(courseId),
                eq("https://example.test/draft.jpg"), eq("learne/course-covers/draft"));
        verify(cleanup).enqueueCourseCover(any(), eq(courseId),
                eq("https://example.test/rejected.jpg"), eq("learne/course-covers/rejected"));
        assertThat(jdbc.queryForObject(
                "select count(*) from courses where id=?", Integer.class, courseId)).isZero();
    }

    private User createTeacher() {
        User teacher = new User();
        teacher.setEmail("cover-delete-teacher@example.com");
        teacher.setFullName("Cover Delete Teacher");
        teacher.setRole(UserRole.TEACHER);
        teacher.setPasswordHash(passwords.encode("Password123"));
        teacher.setActive(true);
        teacher.setLocked(false);
        teacher.setMustChangePassword(false);
        return users.save(teacher);
    }

    private void insertRevision(long courseId, long teacherId, int number, String status, String cover) {
        jdbc.update("""
                insert into course_revisions(
                    course_id,teacher_id,revision_number,status,name,type,level,order_index,
                    pricing_type,cover_image_url,cover_image_public_id)
                values (?,?,?,?,'Delete Covers','GRAMMAR','A1',0,'FREE',?,?)
                """, courseId, teacherId, number, status,
                "https://example.test/" + cover + ".jpg", "learne/course-covers/" + cover);
    }
}

package com.learne.backend.admin.content;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.teacher.content.CourseCoverImageService;
import com.learne.backend.teacher.content.audio.service.MediaCleanupProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.cache.invalidation-outbox.fixed-delay=3600000")
class AdminCourseCoverReviewApiTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private MockMvc mockMvc;
    @Autowired private CourseCoverImageService coverImages;
    @MockitoBean private MediaCleanupProducer cleanup;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        TestDatabaseCleaner.clean(jdbc);
    }

    @Test
    void currentCoverProposalCanBeApprovedThroughAdminApi() throws Exception {
        User teacher = createUser("admin-cover-teacher@example.com", UserRole.TEACHER);
        User admin = createUser("admin-cover-admin@example.com", UserRole.ADMIN);
        jdbc.update("""
                insert into courses(teacher_id, name, type, level, status, image_url, image_public_id)
                values (?, 'Review Course', 'GRAMMAR', 'A1', 'DRAFT',
                    'https://example.test/current.jpg', 'current-cover')
                """, teacher.getId());
        long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Review Course'", Long.class);
        jdbc.update("""
                insert into course_cover_reviews
                    (course_id, teacher_id, image_url, image_public_id, status)
                values (?, ?, 'https://example.test/proposed.jpg', 'proposed-cover', 'PENDING_REVIEW')
                """, courseId, teacher.getId());
        long reviewId = jdbc.queryForObject(
                "select max(id) from course_cover_reviews where course_id = ?", Long.class, courseId);
        jdbc.update("""
                insert into course_pending_review_snapshots(course_id, snapshot_kind, cover_review_id)
                values (?, 'COVER_ONLY', ?)
                """, courseId, reviewId);
        String token = tokenFor(admin.getEmail());

        mockMvc.perform(get("/api/admin/content/course-covers/pending")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(reviewId))
                .andExpect(jsonPath("$.content[0].currentCoverImageUrl")
                        .value("https://example.test/current.jpg"))
                .andExpect(jsonPath("$.content[0].proposedCoverImageUrl")
                        .value("https://example.test/proposed.jpg"));
        mockMvc.perform(post("/api/admin/content/course-covers/{id}/approve", reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select image_url from courses where id = ?", String.class, courseId))
                .isEqualTo("https://example.test/proposed.jpg");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from user_notifications
                where user_id = ? and type = 'COURSE_COVER_REVIEW_APPROVED'
                """, Integer.class, teacher.getId())).isOne();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from catalog_invalidation_outbox
                where course_id = ? and status = 'PENDING'
                """, Integer.class, courseId)).isOne();
    }

    @Test
    void removingPublicCoverCreatesCatalogInvalidationEvent() {
        User teacher = createUser("cover-removal-teacher@example.com", UserRole.TEACHER);
        jdbc.update("""
                insert into courses(teacher_id, name, type, level, status, image_url, image_public_id)
                values (?, 'Removal Course', 'GRAMMAR', 'A1', 'DRAFT',
                    'https://example.test/current.jpg', 'current-cover')
                """, teacher.getId());
        long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Removal Course'", Long.class);

        coverImages.remove(teacher, courseId);

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select image_url from courses where id = ?", String.class, courseId)).isNull();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from catalog_invalidation_outbox
                where course_id = ? and status = 'PENDING'
                """, Integer.class, courseId)).isOne();
    }

    @Test
    void cancellingPendingCoverClearsThePersistedMediaReference() {
        User teacher = createUser("cover-cancel-teacher@example.com", UserRole.TEACHER);
        jdbc.update("""
                insert into courses(teacher_id,name,type,level,status,image_url,image_public_id)
                values (?,'Cancel Course','GRAMMAR','A1','DRAFT',
                    'https://example.test/current.jpg','current-cover')
                """, teacher.getId());
        long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Cancel Course'", Long.class);
        jdbc.update("""
                insert into course_cover_reviews(
                    course_id,teacher_id,image_url,image_public_id,status)
                values (?,?,'https://example.test/pending.jpg','pending-cover','PENDING_REVIEW')
                """, courseId, teacher.getId());
        long reviewId = jdbc.queryForObject(
                "select max(id) from course_cover_reviews where course_id = ?", Long.class, courseId);
        jdbc.update("""
                insert into course_pending_review_snapshots(course_id,snapshot_kind,cover_review_id)
                values (?,'COVER_ONLY',?)
                """, courseId, reviewId);

        coverImages.cancelPending(teacher, courseId);

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForMap("""
                select status,image_url,image_public_id
                from course_cover_reviews where id = ?
                """, reviewId))
                .containsEntry("status", "CANCELLED")
                .containsEntry("image_url", null)
                .containsEntry("image_public_id", null);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from course_pending_review_snapshots where course_id = ?
                """, Integer.class, courseId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select image_url from courses where id = ?", String.class, courseId))
                .isEqualTo("https://example.test/current.jpg");
    }

    @Test
    void teacherCanReloadApprovedPendingAndRevisionCoverState() throws Exception {
        User teacher = createUser("cover-state-teacher@example.com", UserRole.TEACHER);
        jdbc.update("""
                insert into courses(teacher_id, name, type, level, status, image_url, image_public_id)
                values (?, 'Cover State Course', 'GRAMMAR', 'A1', 'APPROVED',
                    'https://example.test/current.jpg', 'current-cover')
                """, teacher.getId());
        long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Cover State Course'", Long.class);
        jdbc.update("""
                insert into course_revisions
                    (course_id, teacher_id, revision_number, status, name, type, level, order_index,
                     cover_image_url, cover_image_public_id)
                values (?, ?, 2, 'DRAFT', 'Cover State Draft', 'GRAMMAR', 'A1', 0,
                    'https://example.test/revision.jpg', 'revision-cover')
                """, courseId, teacher.getId());
        jdbc.update("""
                insert into course_cover_reviews
                    (course_id, teacher_id, image_url, image_public_id, status)
                values (?, ?, 'https://example.test/pending.jpg', 'pending-cover', 'PENDING_REVIEW')
                """, courseId, teacher.getId());
        long reviewId = jdbc.queryForObject(
                "select max(id) from course_cover_reviews where course_id = ?", Long.class, courseId);
        jdbc.update("""
                insert into course_pending_review_snapshots(course_id, snapshot_kind, cover_review_id)
                values (?, 'COVER_ONLY', ?)
                """, courseId, reviewId);

        mockMvc.perform(get("/api/teacher/content/courses/{id}/cover-image", courseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(teacher.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicImageUrl").value("https://example.test/current.jpg"))
                .andExpect(jsonPath("$.pendingSnapshot.kind").value("COVER_ONLY"))
                .andExpect(jsonPath("$.pendingSnapshot.id").value(reviewId))
                .andExpect(jsonPath("$.pendingSnapshot.proposedImageUrl").value("https://example.test/pending.jpg"))
                .andExpect(jsonPath("$.latestCoverReview.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.revisionCandidate.status").value("DRAFT"))
                .andExpect(jsonPath("$.revisionCandidate.proposedImageUrl").value("https://example.test/revision.jpg"));
    }

    @Test
    void teacherCannotReadAnotherTeachersCoverState() throws Exception {
        User owner = createUser("cover-state-owner@example.com", UserRole.TEACHER);
        User other = createUser("cover-state-other@example.com", UserRole.TEACHER);
        jdbc.update("""
                insert into courses(teacher_id, name, type, level, status)
                values (?, 'Private Cover State', 'GRAMMAR', 'A1', 'DRAFT')
                """, owner.getId());
        long courseId = jdbc.queryForObject(
                "select id from courses where name = 'Private Cover State'", Long.class);

        mockMvc.perform(get("/api/teacher/content/courses/{id}/cover-image", courseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(other.getEmail())))
                .andExpect(status().isNotFound());
    }

    private User createUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setRole(role);
        user.setPasswordHash(passwords.encode("Password123"));
        user.setActive(true);
        user.setLocked(false);
        user.setMustChangePassword(false);
        return users.save(user);
    }

    private String tokenFor(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}

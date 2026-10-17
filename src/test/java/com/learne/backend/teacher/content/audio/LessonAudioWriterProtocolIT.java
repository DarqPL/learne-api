package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.admin.content.service.AdminContentReviewService;
import com.learne.backend.domain.auth.*;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.dto.LessonRequest;
import com.learne.backend.teacher.content.enrichment.*;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import com.learne.backend.teacher.content.service.TeacherContentService;
import com.learne.backend.shared.api.ApiException;
import jakarta.persistence.EntityManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LessonAudioWriterProtocolIT extends AudioPostgresTestSupport {
    @Autowired AdminContentReviewService adminReview;
    @MockitoSpyBean CourseWriteLockService locks;
    @MockitoSpyBean TeacherContentService editWriter;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void aiResponse() {
        when(knowledge.enrichKnowledgePoints(any())).thenAnswer(i -> {
            outsideTransaction();
            return response(i.getArgument(0));
        });
    }
    private CourseKnowledgePointResponse response(CourseKnowledgePointRequest request) {
        return new CourseKnowledgePointResponse(request.questions().stream()
                .map(q -> new CourseKnowledgePointResponse.Item(q.questionId(), "daily_routine")).toList());
    }
    private LessonRequest edit(String title) {
        return new LessonRequest(title, "JSON edit", "I wake up early.", SkillType.LISTENING, 1, 10, false);
    }

    @Test
    void jsonStartsAtScalarLookupThenReloadsUnderLockAndKeepsNewAudio() throws Exception {
        try (Gate gate = new Gate()) {
            // Entry is after the writer's real scalar discovery, before any course lock or graph load.
            doAnswer(i -> {
                if (Thread.currentThread().getName().startsWith("pool-")) {
                    gate.entered.countDown(); await(gate.release);
                }
                return i.callRealMethod();
            }).when(org.springframework.test.util.AopTestUtils.<CourseWriteLockService>getUltimateTargetObject(locks))
                    .teacherCourse(draft.courseId(), draft.teacher());
            var json = workers.submit(() -> content.updateLesson(draft.teacher(), draft.liveLessonId(), edit("Edited")));
            gate.entered();
            upload();
            gate.close(); get(json);
            assertMirrored(state("new"));
            assertThat(jdbc.queryForObject("select title from lessons where id=?", String.class, draft.liveLessonId())).isEqualTo("Edited");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void jsonEditOnlyConflictsWhenRevisionChildIdentityWasRebuilt(boolean rebuild) throws Exception {
        if (!rebuild) jdbc.update("update courses set published_at=current_timestamp where id=?", draft.courseId());
        try (var gate = gateUpload()) {
            var uploading = workers.submit(this::upload);
            gate.entered();
            if (rebuild) {
                courseBlocks(() -> content.updateLesson(draft.teacher(), draft.liveLessonId(), edit("Rebuilt")));
            } else {
                // Revision-only JSON metadata editing keeps identity and both audio pairs valid.
                assertThat(jdbc.queryForObject("select count(*) from lessons where id=?", Integer.class,
                        draft.revisionLessonId())).isZero();
                courseBlocks(() -> content.updateLesson(draft.teacher(), draft.revisionLessonId(), edit("Metadata edit")));
            }
            gate.close();
            if (rebuild) {
                conflict(uploading);
                assertThat(jdbc.queryForObject("select count(*) from lesson_revisions where id=?", Integer.class, draft.revisionLessonId())).isZero();
                assertMirrored(LessonAudioState.empty());
            } else {
                get(uploading);
                assertPair("lesson_revisions", draft.revisionLessonId(), state("new"));
            }
        }
    }

    @Test
    void submitWinsAndUploadCannotWritePendingRevision() throws Exception {
        try (var gate = gateUpload()) {
            var uploading = workers.submit(this::upload); gate.entered();
            content.submitCourse(draft.teacher(), draft.courseId());
            gate.close(); conflict(uploading);
            assertMirrored(LessonAudioState.empty());
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("PENDING_REVIEW");
        }
    }

    @Test
    void uploadWinsWhileSubmitEnrichmentPausedAndStaleEnrichmentNeverApplies() throws Exception {
        try (Gate gate = new Gate()) {
            doAnswer(i -> {
                outsideTransaction(); gate.entered.countDown(); await(gate.release); return response(i.getArgument(0));
            }).when(knowledge).enrichKnowledgePoints(any());
            var submit = workers.submit(() -> content.submitCourse(draft.teacher(), draft.courseId()));
            gate.entered(); upload(); gate.close(); conflict(submit);
            assertMirrored(state("new"));
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("select knowledge_point from question_revisions where lesson_revision_id=?", String.class, draft.revisionLessonId())).isEqualTo("daily-routine");
        }
    }

    @Test
    void submitAfterUploadUsesFreshAudio() throws Exception {
        upload();
        content.submitCourse(draft.teacher(), draft.courseId());
        assertMirrored(state("new"));
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void createEditRevisionCannotRetargetOldUploadToken() throws Exception {
        try (var gate = gateUpload()) {
            var uploading = workers.submit(() -> audio.upload(draft.teacher(), AudioTargetKind.LIVE,
                    draft.liveLessonId(), file("new"))); gate.entered();
            content.submitCourse(draft.teacher(), draft.courseId());
            courseBlocks(() -> content.createEditRevision(draft.teacher(), draft.courseId()));
            gate.close(); conflict(uploading);
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("SUPERSEDED");
            assertThat(jdbc.queryForObject("select count(*) from lesson_revisions where audio_public_id=?", Integer.class, state("new").publicId())).isZero();
            assertThat(reads.preflight(draft.teacher(), AudioTargetKind.LIVE, draft.liveLessonId()).revisionId())
                    .isNotEqualTo(draft.revisionId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve-course", "approve-revision", "reject-course", "reject-revision"})
    void adminPathsWaitOnCourseBeforeTakingRevisionLock(String path) throws Exception {
        upload();
        content.submitCourse(draft.teacher(), draft.courseId());
        User admin = admin();
        courseBlocks(() -> review(path, admin));
        boolean approve = path.startsWith("approve");
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId()))
                .isEqualTo(approve ? "APPROVED" : "REJECTED");
        assertMirrored(state("new"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve-course", "approve-revision", "reject-course", "reject-revision"})
    void supersededRevisionCannotBePublishedOrReviewed(String path) {
        content.submitCourse(draft.teacher(), draft.courseId());
        content.createEditRevision(draft.teacher(), draft.courseId());
        User admin = admin();
        assertThatThrownBy(() -> review(path, admin)).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        assertThat(jdbc.queryForObject("select published_at from courses where id=?", java.sql.Timestamp.class, draft.courseId())).isNull();
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("SUPERSEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve-course", "approve-revision", "reject-course", "reject-revision"})
    void adminRevalidatesAfterWaitingForUncommittedSupersedingWriter(String path) throws Exception {
        content.submitCourse(draft.teacher(), draft.courseId());
        User admin = admin();
        AtomicInteger writerPid = new AtomicInteger(), adminPid = new AtomicInteger();
        try (Gate beforeCommit = new Gate(); Gate adminAtCourse = new Gate()) {
            // The outer Spring transaction interceptor still owns the transaction when the
            // target spy returns. Execute the entire real business method, including save,
            // then flush its changes before pausing immediately before proxy completion.
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                entityManager.flush();
                writerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                assertThat(jdbc.queryForObject("select status from course_revisions where id=?",
                        String.class, draft.revisionId())).isEqualTo("SUPERSEDED");
                assertThat(jdbc.queryForObject("select count(*) from course_revisions where course_id=? and status='DRAFT'",
                        Integer.class, draft.courseId())).isEqualTo(1);
                beforeCommit.entered.countDown();
                await(beforeCommit.release);
                return result;
            }).when(AopTestUtils.<TeacherContentService>getUltimateTargetObject(editWriter))
                    .createEditRevision(draft.teacher(), draft.courseId());
            doAnswer(invocation -> {
                adminPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                adminAtCourse.entered.countDown();
                return invocation.callRealMethod();
            }).when(AopTestUtils.<CourseWriteLockService>getUltimateTargetObject(locks))
                    .adminCourse(draft.courseId());

            Future<?> writer = workers.submit(() -> content.createEditRevision(draft.teacher(), draft.courseId()));
            beforeCommit.entered();
            // These reads run on a separate, autocommit connection: flushed changes are
            // invisible until the paused writer's real service transaction commits.
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?",
                    String.class, draft.revisionId())).isEqualTo("PENDING_REVIEW");
            assertThat(jdbc.queryForObject("select count(*) from course_revisions where course_id=?",
                    Integer.class, draft.courseId())).isEqualTo(1);

            Future<?> reviewing = workers.submit(() -> review(path, admin));
            adminAtCourse.entered();
            assertThat(adminPid.get()).isNotEqualTo(writerPid.get());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean blockedOnCourse = false;
            while (System.nanoTime() < deadline && !reviewing.isDone()) {
                blockedOnCourse = Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from pg_stat_activity
                          where pid=? and ? = any(pg_blocking_pids(pid))
                            and wait_event_type='Lock' and lower(query) like '%from courses%')
                        """, Boolean.class, adminPid.get(), writerPid.get()));
                if (blockedOnCourse) break;
                Thread.onSpinWait();
            }
            assertThat(blockedOnCourse).as("%s waits on the real superseding writer's course lock", path).isTrue();
            assertThat(reviewing.isDone()).isFalse();
            assertThat(writer.isDone()).isFalse();
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?",
                    String.class, draft.revisionId())).isEqualTo("PENDING_REVIEW");

            beforeCommit.close();
            get(writer);
            assertThatThrownBy(() -> get(reviewing)).isInstanceOfSatisfying(ExecutionException.class,
                    failure -> assertThat(failure.getCause()).isInstanceOfSatisfying(ApiException.class,
                            api -> assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST)));

            // Fresh JDBC reads prove neither the superseded target nor the new draft was
            // reviewed or published, including the course-ID paths' latest-target reload.
            assertThat(jdbc.queryForList("select status from course_revisions where course_id=? order by revision_number",
                    String.class, draft.courseId())).containsExactly("SUPERSEDED", "DRAFT");
            assertThat(jdbc.queryForObject("""
                    select count(*) from course_revisions where course_id=?
                      and (reviewed_by is not null or reviewed_at is not null or published_at is not null
                           or rejection_reason is not null)
                    """, Integer.class, draft.courseId())).isZero();
            var course = jdbc.queryForMap("select status, reviewed_by, reviewed_at, published_at, rejection_reason from courses where id=?",
                    draft.courseId());
            assertThat(course.get("status")).isEqualTo("DRAFT");
            assertThat(course.get("reviewed_by")).isNull();
            assertThat(course.get("reviewed_at")).isNull();
            assertThat(course.get("published_at")).isNull();
            assertThat(course.get("rejection_reason")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"clone-first", "cleanup-before-clone", "publish-first", "cleanup-before-publish"})
    void cleanupVersusCloneOrPublishRetainsReferencedAsset(String order) throws Exception {
        seedAudio("kept");
        content.submitCourse(draft.teacher(), draft.courseId());
        User admin = admin();
        Callable<?> writer = () -> {
            if (order.contains("clone")) return content.createEditRevision(draft.teacher(), draft.courseId());
            return adminReview.approveRevision(admin, draft.revisionId());
        };
        Callable<?> cleaning = () -> { cleanup.tryCleanup(draft.courseId(), java.util.List.of(state("kept"))); return true; };
        if (order.startsWith("cleanup")) courseRace(cleaning, writer);
        else courseRace(writer, cleaning);
        verify(storage, never()).delete(eq(state("kept").publicId()), any());
        assertThat(jdbc.queryForObject("select count(*) from lesson_revisions where audio_public_id=?", Integer.class, state("kept").publicId())).isPositive();
    }

    private User admin() {
        User user = new User(); user.setEmail("admin@example.com"); user.setFullName("Admin");
        user.setPasswordHash("hash"); user.setRole(UserRole.ADMIN); return users.save(user);
    }
    private Object review(String path, User admin) {
        return switch (path) {
            case "approve-course" -> adminReview.approveCourse(admin, draft.courseId());
            case "approve-revision" -> adminReview.approveRevision(admin, draft.revisionId());
            case "reject-course" -> adminReview.rejectCourse(admin, draft.courseId(), "Needs work");
            case "reject-revision" -> adminReview.rejectRevision(admin, draft.revisionId(), "Needs work");
            default -> throw new IllegalArgumentException(path);
        };
    }
}

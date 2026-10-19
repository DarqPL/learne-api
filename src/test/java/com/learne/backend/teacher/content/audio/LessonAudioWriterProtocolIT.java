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
    @Autowired com.learne.backend.teacher.content.submission.SubmissionAcceptanceService acceptance;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FAILED,DRAFT","FAILED,REJECTED","FAILED,PENDING_REVIEW","FAILED,APPROVED",
            "SUCCEEDED,DRAFT","SUCCEEDED,REJECTED","SUCCEEDED,PENDING_REVIEW","SUCCEEDED,APPROVED"})
    void terminalSubmissionReleasesGuardButRetainsContentStatusRules(String terminal, String status) {
        var attempt=acceptance.accept(draft.teacher().getId(),draft.courseId());
        jdbc.update("update course_submission_attempts set state=?,completed_at=clock_timestamp() where id=?",terminal,attempt.id());
        jdbc.update("update courses set status=?,published_at=case when ?='APPROVED' then current_timestamp else null end where id=?",status,status,draft.courseId());
        jdbc.update("update course_revisions set status=? where id=?",status,draft.revisionId());
        var request=new com.learne.backend.teacher.content.dto.CourseRequest("Edited after terminal",null,
                com.learne.backend.teacher.content.enums.CourseType.COMPREHENSIVE,"A2",2,
                com.learne.backend.teacher.content.enums.CoursePricingType.FREE,null);
        if(status.equals("DRAFT") || status.equals("REJECTED")) {
            assertThat(reads.preflight(draft.teacher(),AudioTargetKind.REVISION,draft.revisionLessonId())).isNotNull();
            content.updateCourse(draft.teacher(),draft.courseId(),request);
            content.updateLesson(draft.teacher(),draft.liveLessonId(),edit("Editable after terminal"));
            assertThat(jdbc.queryForObject("select title from lessons where id=?",String.class,draft.liveLessonId())).isEqualTo("Editable after terminal");
        } else {
            assertThatThrownBy(() -> content.updateCourse(draft.teacher(),draft.courseId(),request))
                    .isInstanceOfSatisfying(ApiException.class,api -> {
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api).isNotInstanceOf(com.learne.backend.shared.api.CodedApiException.class);
                    });
            conflict(() -> audio.delete(draft.teacher(),AudioTargetKind.REVISION,draft.revisionLessonId()));
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,draft.revisionId())).isEqualTo(status);
            // Existing supported way to edit reviewed content remains available after terminal state.
            content.createEditRevision(draft.teacher(),draft.courseId());
            content.updateCourse(draft.teacher(),draft.courseId(),request);
        }
        verifyNoInteractions(knowledge);
    }

    @Test
    void httpMutationsCannotBypassGuardAndOptionalCodeKeepsLegacyEnvelope() throws Exception {
        long topic=jdbc.queryForObject("select topic_id from lessons where id=?",Long.class,draft.liveLessonId());
        long question=jdbc.queryForObject("select id from questions where lesson_id=?",Long.class,draft.liveLessonId());
        acceptance.accept(draft.teacher().getId(),draft.courseId());
        for(String path:java.util.List.of("courses/"+draft.courseId(),"topics/"+topic,"lessons/"+draft.liveLessonId(),
                "questions/"+question,"lessons/"+draft.revisionLessonId())) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/teacher/content/"+path)
                            .header("Authorization","Bearer "+draft.accessToken()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("COURSE_SUBMISSION_IN_PROGRESS"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").isString());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/teacher/content/courses/"+draft.courseId()+"/submit")
                        .header("Authorization","Bearer "+draft.accessToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        for(String operation:java.util.List.of("edit-revision")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/teacher/content/courses/"+draft.courseId()+"/"+operation)
                            .header("Authorization","Bearer "+draft.accessToken()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("COURSE_SUBMISSION_IN_PROGRESS"));
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/teacher/content/courses/"+draft.courseId())
                        .header("Authorization","Bearer "+draft.accessToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/teacher/content/courses/"+draft.courseId()+"/export")
                        .header("Authorization","Bearer "+draft.accessToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/teacher/content/courses/9223372036854775807")
                        .header("Authorization","Bearer "+draft.accessToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("{\"message\":\"Course not found.\"}",org.springframework.test.json.JsonCompareMode.STRICT));
        var mapper=new tools.jackson.databind.json.JsonMapper();
        assertThat(mapper.writeValueAsString(new com.learne.backend.shared.api.ApiErrorResponse("Legacy"))).isEqualTo("{\"message\":\"Legacy\"}");
        assertThat(jdbc.queryForObject("select count(*) from courses where id=?",Integer.class,draft.courseId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from questions where id=?",Integer.class,question)).isEqualTo(1);
        verifyNoInteractions(knowledge);
    }

    @Test
    void acceptanceDuringExternalUploadRejectsFinalPersistenceAndCompensates() throws Exception {
        try (var gate = gateUpload()) {
            var uploading = workers.submit(this::upload);
            gate.entered();
            try {
                assertThat(acceptance.accept(draft.teacher().getId(), draft.courseId()).status()).isEqualTo("QUEUED");
                assertThat(uploading.isDone()).isFalse();
            } finally { gate.close(); }
            assertThatThrownBy(() -> get(uploading)).isInstanceOfSatisfying(ExecutionException.class,
                    failure -> assertThat(failure.getCause()).isInstanceOfSatisfying(
                            com.learne.backend.shared.api.CodedApiException.class,
                            api -> assertThat(api.getCode()).isEqualTo("COURSE_SUBMISSION_IN_PROGRESS")));
            assertMirrored(LessonAudioState.empty());
            verify(storage).delete(eq(state("new").publicId()), any());
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class,
                    draft.revisionId())).isEqualTo("DRAFT");
        }
    }

    @BeforeEach
    void aiResponse() {
        when(knowledge.enrichKnowledgePoints(any(), org.mockito.ArgumentMatchers.eq(com.learne.backend.teacher.content.enrichment.EnrichmentContract.V1))).thenAnswer(i -> {
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
            submissions.submit(draft.teacher().getId(), draft.courseId());
            gate.close(); conflict(uploading);
            assertMirrored(LessonAudioState.empty());
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("PENDING_REVIEW");
        }
    }

    @Test
    void acceptedSubmissionBlocksUploadWhileEnrichmentIsPaused() throws Exception {
        try (Gate gate = new Gate()) {
            doAnswer(i -> {
                outsideTransaction(); gate.entered.countDown(); await(gate.release); return response(i.getArgument(0));
            }).when(knowledge).enrichKnowledgePoints(any(), org.mockito.ArgumentMatchers.eq(com.learne.backend.teacher.content.enrichment.EnrichmentContract.V1));
            var submit = workers.submit(() -> submissions.submit(draft.teacher().getId(), draft.courseId()));
            gate.entered(); conflict(this::upload); gate.close(); get(submit);
            assertMirrored(LessonAudioState.empty());
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("PENDING_REVIEW");
        }
    }

    @Test
    void submitAfterUploadUsesFreshAudio() throws Exception {
        upload();
        submissions.submit(draft.teacher().getId(), draft.courseId());
        assertMirrored(state("new"));
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void createEditRevisionCannotRetargetOldUploadToken() throws Exception {
        try (var gate = gateUpload()) {
            var uploading = workers.submit(() -> audio.upload(draft.teacher(), AudioTargetKind.LIVE,
                    draft.liveLessonId(), file("new"))); gate.entered();
            submissions.submit(draft.teacher().getId(), draft.courseId());
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
        submissions.submit(draft.teacher().getId(), draft.courseId());
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
        submissions.submit(draft.teacher().getId(), draft.courseId());
        content.createEditRevision(draft.teacher(), draft.courseId());
        User admin = admin();
        assertThatThrownBy(() -> review(path, admin)).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        assertThat(jdbc.queryForObject("select published_at from courses where id=?", java.sql.Timestamp.class, draft.courseId())).isNull();
        assertThat(jdbc.queryForObject("select status from course_revisions where id=?", String.class, draft.revisionId())).isEqualTo("SUPERSEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve-course", "approve-revision", "reject-course", "reject-revision"})
    void adminRevalidatesAfterWaitingForUncommittedSupersedingWriter(String path) throws Exception {
        submissions.submit(draft.teacher().getId(), draft.courseId());
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
        submissions.submit(draft.teacher().getId(), draft.courseId());
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

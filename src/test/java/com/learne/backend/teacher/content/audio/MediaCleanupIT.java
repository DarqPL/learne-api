package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobMaintenanceService;
import com.learne.backend.jobs.service.JobStore;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.teacher.content.CourseCoverImageService;
import com.learne.backend.teacher.content.audio.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.jobs.service.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(20)
@TestPropertySource(properties="app.cloudinary.cloud-name=test-cloud")
class MediaCleanupIT extends AudioPostgresTestSupport {
    @Autowired JobStore store;
    @Autowired MediaCleanupProducer producer;
    @Autowired JobMaintenanceService maintenance;
    @Autowired LessonAudioCleanupDecisionService decisions;
    @Autowired CourseCoverCleanupDecisionService coverDecisions;
    @Autowired CourseCoverImageService coverImages;
    @Autowired com.learne.backend.teacher.content.service.TeacherContentService content;
    @Autowired com.learne.backend.storage.config.CloudinaryProperties cloud;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    long[] audioCleanupFixture() {
        cloud.setCloudName("test-cloud");
        long teacher=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('media@test.test','test','Teacher','TEACHER') returning id",Long.class);
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacher);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,teacher);
        long topic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        var state=LessonAudioCleanupServiceTests.state("a");
        long lesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,audio_url,audio_public_id) values (?,?,'Lesson','READING',?,?) returning id",Long.class,topic,teacher,state.url(),state.publicId());
        return new long[]{course,lesson};
    }
    void enqueue(UUID event,long course) { new TransactionTemplate(manager).executeWithoutResult(s->producer.enqueue(event,course,List.of(LessonAudioCleanupServiceTests.state("a")))); }
    JobLease claim() { return store.claim(QueueName.MEDIA,Map.of(JobType.MEDIA_CLEANUP,Set.of(1)),Duration.ofSeconds(30)).orElseThrow(); }
    String state(UUID id) { return jdbc.queryForObject("select state from background_jobs where id=?",String.class,id); }
    String coverUrl(String publicId) { return "https://res.cloudinary.com/test-cloud/image/upload/" + publicId + ".jpg"; }
    void seedCover(String publicId) {
        jdbc.update("update courses set image_url=?,image_public_id=? where id=?",
                coverUrl(publicId),publicId,draft.courseId());
    }
    void enqueueCover(UUID eventId,String url,String publicId) {
        new TransactionTemplate(manager).executeWithoutResult(tx->
                producer.enqueueCourseCover(eventId,draft.courseId(),url,publicId));
    }
    MediaCleanupHandler coverHandler(MediaDeletePort delete) {
        return new MediaCleanupHandler(store,decisions,coverDecisions,delete,mapper);
    }
    void awaitCourseLockWaiter() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            int blocked=jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() and cardinality(pg_blocking_pids(pid))>0",
                    Integer.class);
            if(blocked>0) return;
            Thread.onSpinWait();
        }
        fail("Expected cleanup worker to wait on the locked course row");
    }
    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
    @Test void leaseLostDuringDecisionPreventsFreshDeleteAndProviderRetryRechecksReferences() {
        var ids=audioCleanupFixture(); jdbc.update("update lesson_revisions set audio_url=null,audio_public_id=null where id=?",ids[1]);
        enqueue(UUID.randomUUID(),ids[0]); var lease=claim(); var deletes=new AtomicInteger();
        var expiringDecision=new LessonAudioCleanupDecisionService(null,null,null,null) {
            @Override public CleanupDecision evaluate(Long course,LessonAudioState candidate) {
                var result=decisions.evaluate(course,candidate);
                jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?",lease.jobId());
                return result;
            }
        };
        new MediaCleanupHandler(store,expiringDecision,coverDecisions,id->deletes.incrementAndGet(),mapper).execute(lease);
        assertThat(deletes).hasValue(0); assertThat(store.recoverExpired(10)).isOne();
        var retryLease=claim();
        var handler=new MediaCleanupHandler(store,decisions,coverDecisions,id->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            deletes.incrementAndGet(); throw new RetryableJobException("BUSY",null);
        },mapper);
        assertThatThrownBy(()->handler.execute(retryLease)).isInstanceOf(RetryableJobException.class);
        assertThat(store.retry(retryLease,java.time.Instant.now(),"BUSY")).isTrue();
        var candidate=LessonAudioCleanupServiceTests.state("a");
        jdbc.update("update lesson_revisions set audio_url=?,audio_public_id=? where id=?",candidate.url(),candidate.publicId(),ids[1]);
        var fresh=claim(); handler.execute(fresh); assertThat(state(fresh.jobId())).isEqualTo("SKIPPED"); assertThat(deletes).hasValue(1);
    }
    @Test void realHistoricalReferenceRetainsThenNewRemovalEventDeletesDespitePriorRetainedAndDead() {
        var ids=audioCleanupFixture(); var event=UUID.randomUUID(); enqueue(event,ids[0]); enqueue(event,ids[0]);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isOne();
        var deletes=new AtomicInteger();
        var handler=new MediaCleanupHandler(store,decisions,coverDecisions,id->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // The decision connection/lock has been released before provider I/O.
            new TransactionTemplate(manager).executeWithoutResult(s->jdbc.queryForObject("select id from courses where id=? for update nowait",Long.class,ids[0]));
            deletes.incrementAndGet();
        },mapper);
        var retained=claim(); handler.execute(retained); assertThat(state(retained.jobId())).isEqualTo("SKIPPED");
        assertThat(deletes).hasValue(0);
        enqueue(UUID.randomUUID(),ids[0]); var dead=claim(); handler.failPermanently(dead,"EXHAUSTED_ATTEMPTS");
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.queryForObject("select id from courses where id=? for update",Long.class,ids[0]);
            jdbc.update("update lesson_revisions set audio_url=null,audio_public_id=null where id=?",ids[1]);
            producer.enqueue(UUID.randomUUID(),ids[0],List.of(LessonAudioCleanupServiceTests.state("a")));
        });
        handler.execute(claim()); assertThat(deletes).hasValue(1);
        assertThat(state(retained.jobId())).isEqualTo("SKIPPED"); assertThat(state(dead.jobId())).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where state='SUCCEEDED'",Integer.class)).isOne();
    }
    @Test void databaseLockFailureRetriesThenFreshDecisionRetainsAndStaleLeaseCannotDelete() throws Exception {
        var ids=audioCleanupFixture(); enqueue(UUID.randomUUID(),ids[0]); var lease=claim(); var deletes=new AtomicInteger();
        var handler=new MediaCleanupHandler(store,decisions,coverDecisions,id->deletes.incrementAndGet(),mapper);
        try(var locked=dataSource.getConnection()) {
            locked.setAutoCommit(false); try(var statement=locked.prepareStatement("select id from courses where id=? for update")) { statement.setLong(1,ids[0]); statement.executeQuery().close(); }
            assertThatThrownBy(()->handler.execute(lease)).isInstanceOf(RetryableJobException.class);
            locked.rollback();
        }
        assertThat(deletes).hasValue(0); assertThat(state(lease.jobId())).isEqualTo("RUNNING");
        handler.execute(lease); assertThat(state(lease.jobId())).isEqualTo("SKIPPED");
        jdbc.update("update lesson_revisions set audio_url=null,audio_public_id=null where id=?",ids[1]);
        enqueue(UUID.randomUUID(),ids[0]); var stale=claim();
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?",stale.jobId());
        handler.execute(stale); assertThat(deletes).hasValue(0);
        assertThat(store.recoverExpired(10)).isOne(); handler.execute(claim()); assertThat(deletes).hasValue(1);
    }
    @Test void missingCourseUnprovenAndAmbiguousReferencesSkipAndAmbientTransactionRejects() {
        var ids=audioCleanupFixture(); var candidate=LessonAudioCleanupServiceTests.state("a");
        assertThat(decisions.evaluate(Long.MAX_VALUE,candidate).reason()).isEqualTo("MISSING_COURSE");
        assertThat(decisions.evaluate(ids[0],new LessonAudioState("https://example.test/a",candidate.publicId())).reason()).isEqualTo("UNPROVEN_PROVENANCE");
        jdbc.update("update lesson_revisions set audio_public_id=null,audio_url='https://example.test/%252F' where id=?",ids[1]);
        assertThat(decisions.evaluate(ids[0],candidate).reason()).isEqualTo("AMBIGUOUS_REFERENCE");
        enqueue(UUID.randomUUID(),ids[0]); var lease=claim(); var deletes=new AtomicInteger();
        var handler=new MediaCleanupHandler(store,decisions,coverDecisions,id->deletes.incrementAndGet(),mapper);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(s->handler.execute(lease))).isInstanceOf(IllegalStateException.class);
        handler.execute(lease); assertThat(state(lease.jobId())).isEqualTo("SKIPPED"); assertThat(deletes).hasValue(0);
    }
    @Test void mandatoryTransactionAndEventDedupeSurviveRetainedAndDeadJobs() {
        var event=UUID.randomUUID(); var state=LessonAudioCleanupServiceTests.state("a");
        assertThatThrownBy(()->producer.enqueue(event,1L,List.of(state))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(s->{producer.enqueue(event,1L,List.of(state,state));producer.enqueue(event,1L,List.of(state));});
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isOne();
        jdbc.update("update background_jobs set state='SKIPPED',completed_at=clock_timestamp(),last_error_code='REFERENCED'");
        tx.executeWithoutResult(s->producer.enqueue(UUID.randomUUID(),1L,List.of(state)));
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isEqualTo(2);
        jdbc.update("update background_jobs set state='DEAD',completed_at=clock_timestamp()");
        tx.executeWithoutResult(s->producer.enqueue(UUID.randomUUID(),1L,List.of(state)));
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isEqualTo(3);
    }
    @Test void terminalReplayIdentityIsKeptInsideThirtyDaysAndPurgedOutsideBoundary() {
        var tx=new TransactionTemplate(manager); var state=LessonAudioCleanupServiceTests.state("a");
        tx.executeWithoutResult(s->{producer.enqueue(UUID.randomUUID(),1L,List.of(state));producer.enqueue(UUID.randomUUID(),1L,List.of(state));});
        var ids=jdbc.queryForList("select id from background_jobs order by id",UUID.class);
        jdbc.update("update background_jobs set state='SKIPPED',completed_at=clock_timestamp()-interval '29 days' where id=?",ids.getFirst());
        jdbc.update("update background_jobs set state='DEAD',completed_at=clock_timestamp()-interval '31 days' where id=?",ids.getLast());
        assertThat(maintenance.maintain().terminalDeleted()).isOne();
        assertThat(jdbc.queryForList("select id from background_jobs",UUID.class)).containsExactly(ids.getFirst());
    }

    @Test void temporaryCoverDeleteFailureKeepsClaimUntilRetrySucceeds() {
        String publicId="learne/course-covers/retry-cover";
        seedCover(publicId);
        coverImages.remove(draft.teacher(),draft.courseId());
        var firstLease=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);
        doThrow(new RetryableJobException("BUSY",null)).doNothing()
                .when(delete).delete(publicId,StorageResourceType.IMAGE);
        var handler=coverHandler(delete);

        assertThatThrownBy(()->handler.execute(firstLease)).isInstanceOf(RetryableJobException.class);
        assertThat(jdbc.queryForList(
                "select status from course_cover_cleanup_claims where public_id=?",String.class,publicId))
                .containsExactly("CLAIMED");
        assertThat(state(firstLease.jobId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForMap(
                "select image_url,image_public_id from courses where id=?",draft.courseId()))
                .containsEntry("image_url",null)
                .containsEntry("image_public_id",null);

        assertThat(store.retry(firstLease,java.time.Instant.now().minusSeconds(1),"BUSY")).isTrue();
        var retryLease=claim();
        handler.execute(retryLease);

        assertThat(state(retryLease.jobId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select count(*) from course_cover_cleanup_claims where public_id=?",Integer.class,publicId)).isZero();
        verify(delete,times(2)).delete(publicId,StorageResourceType.IMAGE);
    }

    @Test void exhaustedCoverCleanupKeepsAnOperatorVisibleDeadClaim() {
        String publicId="learne/course-covers/dead-cover";
        seedCover(publicId);
        coverImages.remove(draft.teacher(),draft.courseId());
        var lease=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);
        doThrow(new RetryableJobException("BUSY",null)).when(delete).delete(publicId,StorageResourceType.IMAGE);
        var handler=coverHandler(delete);

        assertThatThrownBy(()->handler.execute(lease)).isInstanceOf(RetryableJobException.class);
        handler.failPermanently(lease,"EXHAUSTED_ATTEMPTS");

        assertThat(state(lease.jobId())).isEqualTo("DEAD");
        assertThat(jdbc.queryForMap(
                "select status,last_error_code from course_cover_cleanup_claims where public_id=?",publicId))
                .containsEntry("status","DEAD")
                .containsEntry("last_error_code","EXHAUSTED_ATTEMPTS");
        assertThat(jdbc.queryForMap(
                "select image_url,image_public_id from courses where id=?",draft.courseId()))
                .containsEntry("image_url",null)
                .containsEntry("image_public_id",null);
        verify(delete,times(1)).delete(publicId,StorageResourceType.IMAGE);
    }

    @Test void referencedCoverSkipsCleanupAndMarksClaimRetainedWithoutBlockingFutureMutations() {
        String publicId="learne/course-covers/referenced-cover";
        seedCover(publicId);
        enqueueCover(UUID.randomUUID(),coverUrl(publicId),publicId);
        var lease=claim();
        jdbc.update("""
                insert into course_cover_cleanup_claims(public_id,course_id,url,status,job_id)
                values (?,?,?,'CLAIMED',?)
                """,publicId,draft.courseId(),coverUrl(publicId),lease.jobId());
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);

        coverHandler(delete).execute(lease);

        assertThat(state(lease.jobId())).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject(
                "select status from course_cover_cleanup_claims where public_id=?",String.class,publicId))
                .isEqualTo("RETAINED");
        assertThat(coverDecisions.isPublicIdClaimed(publicId)).isFalse();
        assertThat(jdbc.queryForMap(
                "select image_url,image_public_id from courses where id=?",draft.courseId()))
                .containsEntry("image_url",coverUrl(publicId))
                .containsEntry("image_public_id",publicId);
        verifyNoInteractions(delete);
    }

    @Test void pendingStandaloneRequestRetainsCoverUntilItsRegistryEntryIsResolved() {
        String publicId="learne/course-covers/pending-request";
        String url=coverUrl(publicId);
        long reviewId=jdbc.queryForObject("""
                insert into course_cover_reviews(
                    course_id,teacher_id,image_url,image_public_id,status)
                values (?,?,?,?,'PENDING_REVIEW') returning id
                """,Long.class,draft.courseId(),draft.teacher().getId(),url,publicId);
        jdbc.update("""
                insert into course_pending_review_snapshots(course_id,snapshot_kind,cover_review_id)
                values (?,'COVER_ONLY',?)
                """,draft.courseId(),reviewId);
        enqueueCover(UUID.randomUUID(),url,publicId);
        var first=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);

        coverHandler(delete).execute(first);

        assertThat(state(first.jobId())).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject(
                "select status from course_cover_cleanup_claims where public_id=?",String.class,publicId))
                .isEqualTo("RETAINED");
        verifyNoInteractions(delete);

        new TransactionTemplate(manager).executeWithoutResult(tx->{
            jdbc.update("delete from course_pending_review_snapshots where course_id=?",draft.courseId());
            jdbc.update("update course_cover_reviews set status='CANCELLED' where id=?",reviewId);
        });
        enqueueCover(UUID.randomUUID(),url,publicId);
        var retry=claim();
        coverHandler(delete).execute(retry);

        assertThat(state(retry.jobId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select count(*) from course_cover_cleanup_claims where public_id=?",Integer.class,publicId)).isZero();
        verify(delete).delete(publicId,StorageResourceType.IMAGE);
    }

    @Test void rejectedEditableRevisionRetainsItsCandidateUntilDetached() {
        String publicId="learne/course-covers/rejected-revision";
        String url=coverUrl(publicId);
        jdbc.update("""
                update course_revisions
                set status='REJECTED',cover_image_url=?,cover_image_public_id=?
                where id=?
                """,url,publicId,draft.revisionId());
        enqueueCover(UUID.randomUUID(),url,publicId);
        var first=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);

        coverHandler(delete).execute(first);

        assertThat(state(first.jobId())).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject(
                "select status from course_cover_cleanup_claims where public_id=?",String.class,publicId))
                .isEqualTo("RETAINED");
        verifyNoInteractions(delete);

        jdbc.update("""
                update course_revisions
                set cover_image_url=null,cover_image_public_id=null
                where id=?
                """,draft.revisionId());
        enqueueCover(UUID.randomUUID(),url,publicId);
        var retry=claim();
        coverHandler(delete).execute(retry);

        assertThat(state(retry.jobId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select count(*) from course_cover_cleanup_claims where public_id=?",Integer.class,publicId)).isZero();
        verify(delete).delete(publicId,StorageResourceType.IMAGE);
    }

    @Test void registryCurrentPendingRevisionRetainsItsCandidate() {
        String publicId="learne/course-covers/pending-revision";
        String url=coverUrl(publicId);
        jdbc.update("""
                update course_revisions
                set status='PENDING_REVIEW',cover_image_url=?,cover_image_public_id=?
                where id=?
                """,url,publicId,draft.revisionId());
        jdbc.update("""
                insert into course_pending_review_snapshots(
                    course_id,snapshot_kind,course_revision_id)
                values (?,'COURSE_REVISION',?)
                """,draft.courseId(),draft.revisionId());
        enqueueCover(UUID.randomUUID(),url,publicId);
        var lease=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);

        coverHandler(delete).execute(lease);

        assertThat(state(lease.jobId())).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject(
                "select status from course_cover_cleanup_claims where public_id=?",String.class,publicId))
                .isEqualTo("RETAINED");
        verifyNoInteractions(delete);
    }

    @Test void workerClaimPreventsReattachingCoverUntilDeleteFinishes() throws Exception {
        String publicId="learne/course-covers/claim-first";
        enqueueCover(UUID.randomUUID(),coverUrl(publicId),publicId);
        var lease=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);
        CountDownLatch deleting=new CountDownLatch(1);
        CountDownLatch finishDelete=new CountDownLatch(1);
        doAnswer(invocation->{
            deleting.countDown();
            await(finishDelete);
            return null;
        }).when(delete).delete(publicId,StorageResourceType.IMAGE);

        Future<?> worker=workers.submit(()->coverHandler(delete).execute(lease));
        await(deleting);
        assertThat(jdbc.queryForObject(
                "select status from course_cover_cleanup_claims where public_id=?",String.class,publicId))
                .isEqualTo("CLAIMED");
        when(storage.uploadCourseCover(any())).thenReturn(
                new StorageUploadResult(coverUrl(publicId),publicId));
        assertThatThrownBy(()->coverImages.upload(draft.teacher(),draft.courseId(),
                new MockMultipartFile("file","cover.jpg","image/jpeg",new byte[]{1})))
                .isInstanceOfSatisfying(ApiException.class,ex->
                        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(jdbc.queryForMap(
                "select image_url,image_public_id from courses where id=?",draft.courseId()))
                .containsEntry("image_url",null)
                .containsEntry("image_public_id",null);
        assertThat(jdbc.queryForObject(
                "select status from course_cover_cleanup_claims where public_id=?",String.class,publicId))
                .isEqualTo("CLAIMED");

        finishDelete.countDown();
        get(worker);
        assertThat(state(lease.jobId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select count(*) from course_cover_cleanup_claims where public_id=?",Integer.class,publicId)).isZero();
        verify(delete,times(1)).delete(publicId,StorageResourceType.IMAGE);
    }

    @Test void uploadCommitsBeforeWaitingWorkerDecidesWhetherToDelete() throws Exception {
        String publicId="learne/course-covers/upload-first";
        enqueueCover(UUID.randomUUID(),coverUrl(publicId),publicId);
        var lease=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);
        CountDownLatch uploadEntered=new CountDownLatch(1);
        CountDownLatch finishUpload=new CountDownLatch(1);
        when(storage.uploadCourseCover(any())).thenAnswer(invocation->{
            uploadEntered.countDown();
            await(finishUpload);
            return new StorageUploadResult(coverUrl(publicId),publicId);
        });

        Future<?> mutation=workers.submit(()->coverImages.upload(draft.teacher(),draft.courseId(),
                new MockMultipartFile("file","cover.jpg","image/jpeg",new byte[]{1})));
        await(uploadEntered);
        Future<?> worker=workers.submit(()->coverHandler(delete).execute(lease));
        awaitCourseLockWaiter();
        finishUpload.countDown();
        get(mutation);
        get(worker);

        assertThat(jdbc.queryForMap(
                "select image_url,image_public_id from courses where id=?",draft.courseId()))
                .containsEntry("image_url",coverUrl(publicId))
                .containsEntry("image_public_id",publicId);
        assertThat(state(lease.jobId())).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject(
                "select count(*) from course_cover_cleanup_claims where public_id=?",Integer.class,publicId)).isZero();
        verifyNoInteractions(delete);
    }

    @Test void removeCommitsBeforeWaitingWorkerClaimsAndDeletesCover() throws Exception {
        String publicId="learne/course-covers/remove-first";
        seedCover(publicId);
        enqueueCover(UUID.randomUUID(),coverUrl(publicId),publicId);
        var lease=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);
        CountDownLatch removalWritten=new CountDownLatch(1);
        CountDownLatch finishRemoval=new CountDownLatch(1);
        Future<?> mutation=workers.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            coverImages.remove(draft.teacher(),draft.courseId());
            removalWritten.countDown();
            awaitUnchecked(finishRemoval);
        }));
        await(removalWritten);
        Future<?> worker=workers.submit(()->coverHandler(delete).execute(lease));
        awaitCourseLockWaiter();
        finishRemoval.countDown();
        get(mutation);
        get(worker);

        assertThat(jdbc.queryForMap(
                "select image_url,image_public_id from courses where id=?",draft.courseId()))
                .containsEntry("image_url",null)
                .containsEntry("image_public_id",null);
        assertThat(state(lease.jobId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select count(*) from course_cover_cleanup_claims where public_id=?",Integer.class,publicId)).isZero();
        verify(delete,times(1)).delete(publicId,StorageResourceType.IMAGE);
    }

    @Test void courseDeletionAllowsWorkerToDeleteCoverAfterCascadingItsReferences() {
        String publicId="learne/course-covers/course-delete";
        seedCover(publicId);
        content.deleteCourse(draft.teacher(),draft.courseId());
        var lease=claim();
        var delete=org.mockito.Mockito.mock(MediaDeletePort.class);

        coverHandler(delete).execute(lease);

        assertThat(jdbc.queryForObject(
                "select count(*) from courses where id=?",Integer.class,draft.courseId())).isZero();
        assertThat(state(lease.jobId())).isEqualTo("SUCCEEDED");
        verify(delete).delete(publicId,StorageResourceType.IMAGE);
    }
}

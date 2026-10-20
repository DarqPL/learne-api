package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.model.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailJobProducer;
import com.learne.backend.notification.NotificationRepository;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import com.learne.backend.teacher.content.repository.*;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.service.PendingReviewTransition;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Owns domain state and durable notification intents under the same fenced transaction. */
@Service
public class SubmissionFinalizationService implements SubmissionCompletionPort {
    private final JdbcTemplate jdbc;
    private final SubmissionRepository repository;
    private final SubmissionProjectionReader projections;
    private final CourseRepository courses;
    private final CourseRevisionRepository revisions;
    private final EntityManager em;
    private final MailJobProducer mail;
    private final NotificationRepository notifications;
    private final CacheInvalidationService cache;
    private final tools.jackson.databind.ObjectMapper mapper;
    public SubmissionFinalizationService(JdbcTemplate jdbc, SubmissionRepository repository, SubmissionProjectionReader projections,
            CourseRepository courses, CourseRevisionRepository revisions, EntityManager em, @org.springframework.context.annotation.Lazy MailJobProducer mail,
            NotificationRepository notifications, CacheInvalidationService cache, tools.jackson.databind.ObjectMapper mapper) {
        this.jdbc=jdbc; this.repository=repository; this.projections=projections; this.courses=courses;
        this.revisions=revisions; this.em=em; this.mail=mail; this.notifications=notifications; this.cache=cache;
        this.mapper=mapper;
    }
    private record Locked(SubmissionRepository.Parent parent, Map<String,Object> row, boolean resources) { }
    private Locked lock(JobLease lease, UUID id) {
        try {
            var payload=mapper.readTree(lease.payloadJson());
            if(!payload.isObject() || payload.size()!=1 || !payload.path("submissionId").isString()
                    || !id.toString().equals(payload.path("submissionId").asString())) throw new IllegalArgumentException();
        } catch(RuntimeException invalid) { throw new IllegalStateException("Invalid submission lease identity"); }
        em.flush(); em.clear();
        var before=repository.parent(id);
        boolean course=!jdbc.queryForList("select id from courses where id=? for update",Long.class,before.courseId()).isEmpty();
        boolean revision=course && !jdbc.queryForList("select id from course_revisions where id=? and course_id=? for update",Long.class,before.revisionId(),before.courseId()).isEmpty();
        var row=jdbc.queryForMap("select * from course_submission_attempts where id=? for update",id);
        var current=repository.parent(id);
        if(!before.equals(current) || !Set.of("QUEUED","PROCESSING").contains(current.state())) throw new IllegalStateException("Inactive submission");
        if(lease.type()!=JobType.COURSE_ENRICHMENT || lease.payloadVersion()!=1 || jdbc.queryForList("""
                select id from background_jobs where id=? and state='RUNNING' and lease_token=?
                and lease_until>clock_timestamp() and type='COURSE_ENRICHMENT' and payload_version=1
                and payload=jsonb_build_object('submissionId',?::text) for update
                """,UUID.class,lease.jobId(),lease.token(),id.toString()).size()!=1) throw new IllegalStateException("Stale submission lease");
        return new Locked(current,row,course && revision);
    }
    @Override @Transactional(timeout=30)
    public void complete(JobLease lease, UUID id) {
        var locked=lock(lease,id); var p=locked.parent(); var row=locked.row();
        if(!locked.resources()) { terminal(lease,locked,false,"SUBMISSION_RESOURCE_UNAVAILABLE"); return; }
        long teacher=((Number)row.get("teacher_id")).longValue();
        boolean eligible=Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from courses c join users u on u.id=c.teacher_id
                join course_revisions r on r.course_id=c.id where c.id=? and r.id=? and r.teacher_id=u.id
                and u.id=? and u.role='TEACHER' and u.is_active and not u.is_locked)
                """,Boolean.class,p.courseId(),p.revisionId(),teacher));
        var header=projections.latestHeader(p.courseId()).orElseThrow();
        if(!eligible || header.revisionId()!=p.revisionId() || header.revisionNumber()!=((Number)row.get("revision_number")).intValue()
                || !header.status().equals(row.get("original_revision_status")) || !Set.of("DRAFT","REJECTED").contains(header.status())
                || !EnrichmentContract.V1.equals(p.contract())) { terminal(lease,locked,false,"SUBMISSION_CONTENT_CHANGED"); return; }
        var capture=projections.capture(p.courseId(),p.contract()); var staged=repository.manifest(id);
        int total=((Number)row.get("total_questions")).intValue();
        boolean valid=capture.globalFingerprint().equals(row.get("fingerprint")) && staged.size()==total
                && capture.manifestEntries().size()==total && ((Number)row.get("processed_questions")).intValue()==total
                && jdbc.queryForObject("select count(*) from course_submission_questions where submission_id=?",Integer.class,id)==total;
        for(int i=0;valid && i<total;i++) {
            var s=staged.get(i); var expected=capture.manifestEntries().get(i);
            valid=s.questionRevisionId()==expected.questionRevisionId() && s.ordinal()==expected.ordinal()
                    && s.inputFingerprint().equals(expected.perQuestionInputFingerprint()) && s.knowledgePoint()!=null
                    && s.difficulty()!=null && s.xpReward()!=null;
        }
        if(!valid) { terminal(lease,locked,false,"SUBMISSION_CONTENT_CHANGED"); return; }
        int[][] counts=jdbc.batchUpdate("update question_revisions set knowledge_point=?,difficulty=?,xp_reward=? where id=?",staged,100,(s,r) -> {
            s.setString(1,r.knowledgePoint()); s.setString(2,r.difficulty()); s.setInt(3,r.xpReward()); s.setLong(4,r.questionRevisionId());
        });
        for(var batch:counts) for(int count:batch) if(count!=1) throw new IllegalStateException("Question set changed");
        var course=courses.findById(p.courseId()).orElseThrow(); var revision=revisions.findById(p.revisionId()).orElseThrow();
        PendingReviewTransition.transitionToPending(course,revision,revisions.findByCourseAndStatus(course,CourseRevisionStatus.PENDING_REVIEW));
        em.flush();
        terminal(lease,locked,true,null);
        var teacherName=jdbc.queryForObject("select full_name from users where id=?",String.class,teacher);
        for(var admin:jdbc.queryForList("select id,email,full_name from users where role='ADMIN' and is_active order by id")) {
            long adminId=((Number)admin.get("id")).longValue();
            mail.enqueue(JobType.COURSE_PENDING_REVIEW_MAIL,"course-pending:"+id+":"+adminId,
                    new MailPayload(adminId,(String)admin.get("email"),(String)admin.get("full_name"),MailTemplate.COURSE_PENDING_REVIEW,
                            null,null,p.courseId(),p.revisionId(),id,null,null,Map.of("courseName",(String)row.get("course_name"),
                            "teacherName",teacherName,"submittedAt",revision.getSubmittedAt().toString())),null);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                evict(() -> cache.evictTeacherDashboard(teacher)); evict(cache::evictGlobalCourseCatalog); evict(cache::evictAdminDashboard);
            }
        });
    }
    private static void evict(Runnable action) { try { action.run(); } catch(RuntimeException ignored) { /* Durable success is already committed. */ } }
    @Override @Transactional(timeout=30)
    public void fail(JobLease lease,UUID id,String code) {
        var locked=lock(lease,id); terminal(lease,locked,false,locked.resources()?code:"SUBMISSION_RESOURCE_UNAVAILABLE");
    }
    private void terminal(JobLease lease,Locked locked,boolean success,String code) {
        var p=locked.parent();
        jdbc.update("update course_submission_attempts set state=?,failure_code=?,completed_at=clock_timestamp() where id=?",success?"SUCCEEDED":"FAILED",code,p.id());
        if(jdbc.update("""
                update background_jobs set state=?,last_error_code=?,lease_token=null,lease_until=null,completed_at=clock_timestamp(),updated_at=clock_timestamp()
                where id=? and state='RUNNING' and lease_token=? and lease_until>clock_timestamp()
                """,success?"SUCCEEDED":"DEAD",code,lease.jobId(),lease.token())!=1) throw new IllegalStateException("Stale submission lease");
        long teacher=((Number)locked.row().get("teacher_id")).longValue();
        // A deleted user cannot own a notification; all other historical resource IDs remain valid scalars.
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from users where id=?)",Boolean.class,teacher)))
            notifications.insertIfAbsent(new NotificationRepository.Intent(UUID.randomUUID(),teacher,
                    success?"COURSE_SUBMISSION_SUCCEEDED":"COURSE_SUBMISSION_FAILED",p.id(),p.courseId(),p.revisionId(),
                    "submission-"+(success?"success:":"failure:")+p.id(),success?"Course submitted for review.":"Course submission failed: "+code));
    }
}

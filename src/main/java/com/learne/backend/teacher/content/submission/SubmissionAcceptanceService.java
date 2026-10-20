package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.model.JobSpec;
import com.learne.backend.jobs.model.JobType;
import com.learne.backend.jobs.service.JobEnqueuer;
import com.learne.backend.jobs.service.JobRegistry;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import com.learne.backend.teacher.content.service.CourseWriteLockService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Standalone admission only; endpoint activation waits for the enrichment worker. */
@Service
public class SubmissionAcceptanceService {
    private final CourseWriteLockService locks;
    private final SubmissionProjectionReader projections;
    private final SubmissionRepository submissions;
    private final JdbcTemplate jdbc;
    private final JobEnqueuer jobs;

    public SubmissionAcceptanceService(CourseWriteLockService locks, SubmissionProjectionReader projections,
            SubmissionRepository submissions, JdbcTemplate jdbc, JobEnqueuer jobs) {
        this.locks=locks;
        this.projections=projections;
        this.submissions=submissions;
        this.jdbc=jdbc;
        this.jobs=jobs;
    }

    @Transactional(timeout = 90)
    public SubmissionResponse accept(long actorId, long courseId) {
        // Entry lock clears the persistence context. Only scalar inputs cross that boundary.
        locks.adminCourse(courseId);
        boolean authorized=Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from courses c join users u on u.id=c.teacher_id
                where c.id=? and u.id=? and u.role='TEACHER' and u.is_active and not u.is_locked)
                """,Boolean.class,courseId,actorId));
        if(!authorized) throw new ApiException(HttpStatus.NOT_FOUND,"Course not found.");
        var header=projections.latestHeader(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"Course revision not found."));
        if(header.teacherId()!=actorId) throw new ApiException(HttpStatus.NOT_FOUND,"Course not found.");
        var active=jdbc.query("""
                select id from course_submission_attempts where course_id=? and state in ('QUEUED','PROCESSING')
                """,(rs,row) -> rs.getObject(1,UUID.class),courseId);
        if(!active.isEmpty()) {
            var existing=submissions.findSummary(active.getFirst()).orElseThrow();
            if(existing.revisionId()!=header.revisionId())
                throw new ApiException(HttpStatus.CONFLICT,"Course has an active submission for another revision.");
            return existing;
        }
        if(!"DRAFT".equals(header.status()) && !"REJECTED".equals(header.status()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"Only draft or rejected content can be submitted for review.");
        if(header.questionCount()<1)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Course must have at least one topic, lesson, and question before review.");
        if(header.questionCount()>1000)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Course submission supports at most 1000 questions.");
        var contract=EnrichmentContract.V1;
        var capture=projections.capture(courseId,contract);
        var id=UUID.randomUUID();
        jdbc.update("""
                insert into course_submission_attempts(id,course_id,revision_id,teacher_id,revision_number,
                    course_name,fingerprint,original_revision_status,state,policy_version,prompt_version,response_version,total_questions)
                values (?,?,?,?,?,?,?,?,'QUEUED',?,?,?,?)
                """,id,courseId,header.revisionId(),actorId,header.revisionNumber(),header.name(),capture.globalFingerprint(),
                header.status(),contract.policyVersion(),contract.promptVersion(),contract.responseVersion(),header.questionCount());
        jdbc.batchUpdate("""
                insert into course_submission_questions(submission_id,question_revision_id,ordinal,input_fingerprint)
                values (?,?,?,?)
                """,capture.manifestEntries(),100,(statement,entry) -> {
                    statement.setObject(1,id); statement.setLong(2,entry.questionRevisionId());
                    statement.setInt(3,entry.ordinal()); statement.setString(4,entry.perQuestionInputFingerprint());
                });
        jobs.enqueue(new JobSpec(UUID.randomUUID(),JobRegistry.queueFor(JobType.COURSE_ENRICHMENT),
                JobType.COURSE_ENRICHMENT,1,0,"course-submit:"+id,"{\"submissionId\":\""+id+"\"}",null,null));
        return submissions.findSummary(id).orElseThrow();
    }
}

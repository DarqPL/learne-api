package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.model.JobLease;
import com.learne.backend.jobs.service.PermanentJobException;
import com.learne.backend.teacher.content.enrichment.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SubmissionCheckpointService {
    private final SubmissionRepository repository;
    private final SubmissionProjectionReader projections;
    private final JdbcTemplate jdbc;
    public SubmissionCheckpointService(SubmissionRepository repository,SubmissionProjectionReader projections,JdbcTemplate jdbc) {
        this.repository=repository; this.projections=projections; this.jdbc=jdbc;
    }
    public record Turn(SubmissionRepository.Parent parent,List<SubmissionRepository.ManifestQuestion> rows,
            SubmissionProjectionReader.BatchContext context) {
        public Turn { rows=List.copyOf(rows); }
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Turn load(UUID id) {
        var parent=repository.parent(id);
        if(!EnrichmentContract.V1.equals(parent.contract())) throw new PermanentJobException("ENRICHMENT_CONTRACT_UNAVAILABLE");
        requireActive(parent);
        var rows=repository.nextBatch(id);
        if(rows.isEmpty()) return new Turn(parent,rows,null);
        try {
            var context=projections.batchContext(parent.revisionId(),rows.stream().map(SubmissionRepository.ManifestQuestion::questionRevisionId).toList(),parent.contract());
            var fingerprints=new HashMap<Long,String>(); rows.forEach(row -> fingerprints.put(row.questionRevisionId(),row.inputFingerprint()));
            if(context.questions().stream().anyMatch(q -> !q.inputFingerprint().equals(fingerprints.get(q.questionRevisionId()))))
                throw new PermanentJobException("HANDLER_ERROR");
            return new Turn(parent,rows,context);
        } catch(IllegalArgumentException invalid) { throw new PermanentJobException("HANDLER_ERROR"); }
    }
    @Transactional(timeout=5)
    public void checkpoint(JobLease lease,Turn turn,List<CourseReviewEnrichmentService.BatchResult> results) {
        var p=turn.parent();
        if(jdbc.queryForList("select id from courses where id=? for update",Long.class,p.courseId()).isEmpty()
                || jdbc.queryForList("select id from course_revisions where id=? and course_id=? for update",Long.class,p.revisionId(),p.courseId()).isEmpty())
            throw new PermanentJobException("HANDLER_ERROR");
        jdbc.queryForList("select id from course_submission_attempts where id=? for update",UUID.class,p.id());
        var current=repository.parent(p.id()); requireActive(current);
        if(!current.equals(p)) throw new IllegalStateException("Submission changed");
        var owned=jdbc.queryForList("""
                select id from background_jobs where id=? and state='RUNNING' and lease_token=?
                and lease_until>clock_timestamp() and type='COURSE_ENRICHMENT' and payload_version=1
                and payload=jsonb_build_object('submissionId',?::text) for update
                """,UUID.class,lease.jobId(),lease.token(),p.id().toString());
        if(owned.size()!=1 || lease.payloadVersion()!=1) throw new IllegalStateException("Stale submission lease");
        var expected=new HashSet<Long>(); turn.rows().forEach(row -> expected.add(row.questionRevisionId()));
        if(expected.isEmpty() || expected.size()>25 || results.size()!=expected.size()
                || !expected.equals(results.stream().map(CourseReviewEnrichmentService.BatchResult::questionId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Invalid whole batch");
        for(var result:results) {
            var row=turn.rows().stream().filter(r -> r.questionRevisionId()==result.questionId()).findFirst().orElseThrow();
            if(jdbc.update("""
                    update course_submission_questions set knowledge_point=?,difficulty=?,xp_reward=?
                    where submission_id=? and question_revision_id=? and input_fingerprint=? and knowledge_point is null
                    """,result.knowledgePoint(),result.difficulty(),result.xpReward(),p.id(),result.questionId(),row.inputFingerprint())!=1)
                throw new IllegalStateException("Batch changed");
        }
        jdbc.update("""
                update course_submission_attempts set state='PROCESSING',processed_questions=
                (select count(*) from course_submission_questions where submission_id=? and knowledge_point is not null) where id=?
                """,p.id(),p.id());
        if(jdbc.update("""
                update background_jobs set state='READY',available_at=clock_timestamp(),attempts=0,
                lease_token=null,lease_until=null,updated_at=clock_timestamp()
                where id=? and state='RUNNING' and lease_token=? and lease_until>clock_timestamp()
                """,lease.jobId(),lease.token())!=1) throw new IllegalStateException("Stale submission lease");
    }
    private static void requireActive(SubmissionRepository.Parent parent) {
        if(!Set.of("QUEUED","PROCESSING").contains(parent.state())) throw new PermanentJobException("HANDLER_ERROR");
    }
}

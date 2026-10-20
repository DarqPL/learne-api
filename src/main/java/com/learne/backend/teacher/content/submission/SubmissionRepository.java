package com.learne.backend.teacher.content.submission;

import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import com.learne.backend.teacher.content.enrichment.EnrichmentContractSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-side staging access. Construction and queue admission perform no database or HTTP I/O. */
@Repository
@Transactional(readOnly = true, timeout = 5)
public class SubmissionRepository implements EnrichmentContractSource {
    private final JdbcTemplate jdbc;

    public SubmissionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Set<EnrichmentContract> activeContracts() {
        return Set.copyOf(new HashSet<>(jdbc.query("""
                select distinct policy_version,prompt_version,response_version
                from course_submission_attempts where state in ('QUEUED','PROCESSING')
                """, (rs, row) -> new EnrichmentContract(rs.getString(1),rs.getString(2),rs.getString(3)))));
    }

    public Optional<SubmissionResponse> findSummary(UUID id) {
        return jdbc.query("""
                select id,course_id,revision_id,state,processed_questions,total_questions,created_at,completed_at,failure_code
                from course_submission_attempts where id=?
                """, (rs, row) -> new SubmissionResponse(rs.getObject("id",UUID.class),rs.getLong("course_id"),
                rs.getLong("revision_id"),rs.getString("state"),rs.getInt("processed_questions"),rs.getInt("total_questions"),
                rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getString("failure_code"),null), id).stream().findFirst();
    }

    public java.util.Map<Long,SubmissionResponse> latestSummaries(List<Long> courseIds) {
        if(courseIds.isEmpty()) return java.util.Map.of();
        var rows=jdbc.query("select distinct on (course_id) id,course_id,revision_id,state,processed_questions,total_questions,created_at,completed_at,failure_code from course_submission_attempts where course_id in ("
                +String.join(",",java.util.Collections.nCopies(courseIds.size(),"?"))+") order by course_id,created_at desc,id desc",
                (rs,row)->new SubmissionResponse(rs.getObject("id",UUID.class),rs.getLong("course_id"),rs.getLong("revision_id"),rs.getString("state"),rs.getInt("processed_questions"),rs.getInt("total_questions"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("completed_at")==null?null:rs.getTimestamp("completed_at").toInstant(),rs.getString("failure_code"),null),courseIds.toArray());
        return rows.stream().collect(java.util.stream.Collectors.toMap(SubmissionResponse::courseId,java.util.function.Function.identity()));
    }

    /** At most 1,000 rows are returned even if a malformed internal writer overfills a manifest. */
    public List<ManifestQuestion> manifest(UUID submissionId) {
        return List.copyOf(jdbc.query("""
                select question_revision_id,ordinal,input_fingerprint,knowledge_point,difficulty,xp_reward
                from course_submission_questions where submission_id=? order by ordinal limit 1000
                """, (rs, row) -> new ManifestQuestion(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getObject(6,Integer.class)), submissionId));
    }

    public record ManifestQuestion(long questionRevisionId, int ordinal, String inputFingerprint,
            String knowledgePoint, String difficulty, Integer xpReward) { }

    public record Parent(UUID id,long courseId,long revisionId,EnrichmentContract contract,String state) { }

    public Parent parent(UUID id) {
        return jdbc.query("select id,course_id,revision_id,policy_version,prompt_version,response_version,state from course_submission_attempts where id=?",
                (rs,row) -> new Parent(rs.getObject(1,UUID.class),rs.getLong(2),rs.getLong(3),
                        new EnrichmentContract(rs.getString(4),rs.getString(5),rs.getString(6)),rs.getString(7)),id)
                .stream().findFirst().orElseThrow(() -> new com.learne.backend.jobs.service.PermanentJobException("HANDLER_ERROR"));
    }

    public List<ManifestQuestion> nextBatch(UUID id) {
        return List.copyOf(jdbc.query("""
                select question_revision_id,ordinal,input_fingerprint,knowledge_point,difficulty,xp_reward
                from course_submission_questions where submission_id=? and knowledge_point is null order by ordinal limit 25
                """,(rs,row) -> new ManifestQuestion(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,Integer.class)),id));
    }
}

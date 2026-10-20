package com.learne.backend.teacher.content.submission;

import com.learne.backend.shared.api.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SubmissionQueryService {
    private final JdbcTemplate jdbc;
    private final SubmissionRepository repository;
    public SubmissionQueryService(JdbcTemplate jdbc,SubmissionRepository repository) { this.jdbc=jdbc; this.repository=repository; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public SubmissionResponse get(long teacher,long course,UUID id) {
        var ids=jdbc.query("""
            select s.id from courses c join course_submission_attempts s on s.course_id=c.id
            where c.id=? and c.teacher_id=?
            """+(id==null?"":" and s.id=?")+" order by s.created_at desc,s.id desc limit 1",
            (rs,row)->rs.getObject(1,UUID.class),id==null?new Object[]{course,teacher}:new Object[]{course,teacher,id});
        if(ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Submission not found.");
        return repository.findSummary(ids.getFirst()).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Submission not found."));
    }
}

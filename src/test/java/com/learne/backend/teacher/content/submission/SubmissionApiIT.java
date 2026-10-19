package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.domain.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SubmissionApiIT extends JobsPostgresTestSupport {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SubmissionAcceptanceService acceptance;
    @Autowired CourseSubmissionHandler handler;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient ai;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.springframework.jdbc.core.JdbcTemplate observedJdbc;
    @Test void unauthenticatedSubmissionApisAreUnauthorized() throws Exception {
        mvc.perform(post("/api/teacher/content/courses/1/submit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teacher/content/courses/1/submission")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teacher/content/courses/1/submissions/"+java.util.UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
    @Test void acceptanceAndOwnerScopedPolling() throws Exception {
        long teacher=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('api@test.test','test','Teacher','TEACHER') returning id",Long.class);
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacher);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,teacher);
        long topic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        long lesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,content) values (?,?,'Lesson','READING','Content') returning id",Long.class,topic,teacher);
        jdbc.update("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text) values (?,?,'SHORT_ANSWER','Question')",lesson,teacher);
        var actor=authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(users.findById(teacher).orElseThrow(),null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER"))));
        var first=acceptance.accept(teacher,course);
        String location="/api/teacher/content/courses/"+course+"/submissions/"+first.id();
        var accepted=mvc.perform(post("/api/teacher/content/courses/{id}/submit",course).with(actor)).andExpect(status().isAccepted()).andExpect(header().string("Location",location)).andExpect(jsonPath("$.id").value(first.id().toString())).andReturn();
        var json=new tools.jackson.databind.json.JsonMapper().readTree(accepted.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(json.propertyNames()).containsExactlyInAnyOrder("id","courseId","revisionId","status","processedQuestions","totalQuestions","createdAt","completedAt","failureCode","message");
        mvc.perform(get(location).with(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(get("/api/teacher/content/courses/{id}/submission",course).with(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first.id().toString()));
        mvc.perform(get("/api/teacher/content/courses/999/submissions/"+first.id()).with(actor)).andExpect(status().isNotFound());
        mvc.perform(get("/api/teacher/content/courses/{id}",course).with(actor)).andExpect(status().isOk())
            .andExpect(jsonPath("$.submission.id").value(first.id().toString())).andExpect(jsonPath("$.editingLocked").value(true))
            .andExpect(jsonPath("$.hasEditableRevision").value(true));
        for(int i=0;i<3;i++) jdbc.update("insert into courses(teacher_id,name,type,level,status) values (?,'Other','COMPREHENSIVE','A1','DRAFT')",teacher);
        org.mockito.Mockito.clearInvocations(observedJdbc);
        mvc.perform(get("/api/teacher/content/courses?size=20").with(actor)).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(4));
        var summaryQueries=org.mockito.Mockito.mockingDetails(observedJdbc).getInvocations().stream()
            .filter(call->call.getMethod().getName().equals("query") && call.getArguments().length>1
                && call.getArguments()[1] instanceof org.springframework.jdbc.core.PreparedStatementSetter
                && call.getArguments()[0] instanceof String sql && sql.contains("from course_submission_attempts"))
            .toList();
        org.assertj.core.api.Assertions.assertThat(summaryQueries).as("one SQL summary query for four courses").hasSize(1);
        org.assertj.core.api.Assertions.assertThat((String)summaryQueries.getFirst().getArguments()[0]).contains("distinct on (course_id)","in (?,?,?,?)");
        long empty=jdbc.queryForObject("select max(id) from courses",Long.class);
        for(long missingCourse:new long[]{empty,999999}) {
            mvc.perform(get("/api/teacher/content/courses/{id}/submission",missingCourse).with(actor)).andExpect(status().isNotFound());
            mvc.perform(get("/api/teacher/content/courses/"+missingCourse+"/submissions/"+first.id()).with(actor)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/teacher/content/courses/"+course+"/submissions/"+java.util.UUID.randomUUID()).with(actor)).andExpect(status().isNotFound());
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown(); if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("AI release timeout");
            var request=(com.learne.backend.teacher.content.enrichment.CourseKnowledgePointRequest)invocation.getArgument(0);
            return new com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse(request.questions().stream().map(q->new com.learne.backend.teacher.content.enrichment.CourseKnowledgePointResponse.Item(q.questionId(),"point")).toList());
        }).when(ai).enrichKnowledgePoints(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(com.learne.backend.teacher.content.enrichment.EnrichmentContract.V1));
        var lease=store.claim(com.learne.backend.jobs.model.QueueName.AI,java.util.Map.of(com.learne.backend.jobs.model.JobType.COURSE_ENRICHMENT,java.util.Set.of(1)),java.time.Duration.ofSeconds(30)).orElseThrow();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var blocked=pool.submit(()->handler.execute(lease));
            try {
                org.assertj.core.api.Assertions.assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                pool.submit(()->mvc.perform(post("/api/teacher/content/courses/{id}/submit",course).with(actor)).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(first.id().toString()))).get(2,java.util.concurrent.TimeUnit.SECONDS);
                org.assertj.core.api.Assertions.assertThat(blocked.isDone()).isFalse();
            } finally { release.countDown(); }
            blocked.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        long foreign=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('foreign@test.test','test','Other','TEACHER') returning id",Long.class);
        jdbc.update("update courses set teacher_id=? where id=?",foreign,course);
        mvc.perform(get(location).with(actor)).andExpect(status().isNotFound());
        mvc.perform(get("/api/teacher/content/courses/{id}/submission",course).with(actor)).andExpect(status().isNotFound());
        jdbc.update("delete from courses where id=?",course);
        mvc.perform(get(location).with(actor)).andExpect(status().isNotFound());
    }
}

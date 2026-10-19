package com.learne.backend.notification;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.domain.auth.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class NotificationApiIT extends JobsPostgresTestSupport {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired NotificationService notifications;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.learne.backend.shared.pagination.CursorTokenService cursors;
    @Test void unauthenticatedNotificationApisAreUnauthorized() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/notifications/"+UUID.randomUUID()+"/read")).andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"insert","read","delete"})
    void pageAndGlobalCountShareSnapshotDuringConcurrentCommit(String mutation) throws Exception {
        long owner=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('snapshot@test.test','test','Teacher','TEACHER') returning id",Long.class);
        UUID first=UUID.randomUUID();
        for(int i=0;i<3;i++) jdbc.update("insert into user_notifications(id,user_id,type,dedupe_key,message) values (?,?,'COURSE_SUBMISSION_FAILED',?,'History')",i==0?first:UUID.randomUUID(),owner,"snapshot:"+i);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");
            entered.countDown();
            if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Snapshot release timed out");
            return invocation.callRealMethod();
        }).when(cursors).encode(org.mockito.ArgumentMatchers.eq("notifications"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyMap());
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var response=pool.submit(()->notifications.list(owner,1,true,null));
            try {
                org.assertj.core.api.Assertions.assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                switch(mutation) {
                    case "insert" -> jdbc.update("insert into user_notifications(id,user_id,type,dedupe_key,message) values (?,?,'COURSE_SUBMISSION_FAILED','concurrent','New')",UUID.randomUUID(),owner);
                    case "read" -> jdbc.update("update user_notifications set read_at=current_timestamp where id=?",first);
                    case "delete" -> jdbc.update("delete from user_notifications where id=?",first);
                }
            } finally { release.countDown(); }
            var page=response.get(5,java.util.concurrent.TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(page.unreadCount()).isEqualTo(3);
            org.assertj.core.api.Assertions.assertThat(page.content()).hasSize(1);
        }
        org.mockito.Mockito.reset(cursors);
        org.assertj.core.api.Assertions.assertThat(notifications.unreadCount(owner).unreadCount()).isEqualTo(mutation.equals("insert")?4:2);
    }

    @Test void uuidTiesAndCursorBindings() {
        long owner=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('cursor@test.test','test','Teacher','TEACHER') returning id",Long.class);
        for(String value:java.util.List.of("00000000-0000-0000-0000-000000000001","ffffffff-ffff-ffff-ffff-ffffffffffff","80000000-0000-0000-0000-000000000001"))
            jdbc.update("insert into user_notifications(id,user_id,type,dedupe_key,message,created_at) values (?,?,'COURSE_SUBMISSION_FAILED',?,'History','2026-01-01T00:00:00Z')",UUID.fromString(value),owner,value);
        var page=notifications.list(owner,1,false,null);
        org.assertj.core.api.Assertions.assertThat(page.content().getFirst().id().toString()).startsWith("ffffffff");
        var next=notifications.list(owner,1,false,page.nextCursor());
        org.assertj.core.api.Assertions.assertThat(next.content().getFirst().id().toString()).startsWith("80000000");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->notifications.list(owner,1,true,page.nextCursor())).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->notifications.list(owner+1,1,false,page.nextCursor())).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        notifications.read(owner,page.content().getFirst().id());
        var unread=notifications.list(owner,1,true,null);
        org.assertj.core.api.Assertions.assertThat(unread.unreadCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(unread.content().getFirst().id().toString()).startsWith("80000000");
        var unreadLast=notifications.list(owner,1,true,unread.nextCursor());
        org.assertj.core.api.Assertions.assertThat(unreadLast.content().getFirst().id().toString()).startsWith("00000000");
        org.assertj.core.api.Assertions.assertThat(unreadLast.hasNext()).isFalse();
        org.assertj.core.api.Assertions.assertThat(unreadLast.nextCursor()).isNull();
        org.assertj.core.api.Assertions.assertThat(unreadLast.unreadCount()).isEqualTo(2);
        for(int invalidSize:new int[]{0,101}) org.assertj.core.api.Assertions.assertThatThrownBy(()->notifications.list(owner,invalidSize,false,null)).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->notifications.read(owner,UUID.randomUUID())).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"course","revision","submission","owner"})
    void resourceAuthorizationAndExactDtoPreserveHistoricalIdentity(String removed) throws Exception {
        long owner=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('resource@test.test','test','Teacher','TEACHER') returning id",Long.class);
        long other=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('resource-other@test.test','test','Other','TEACHER') returning id",Long.class);
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,owner);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') returning id",Long.class,course,owner);
        UUID submission=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("insert into course_submission_attempts(id,course_id,revision_id,teacher_id,revision_number,course_name,fingerprint,original_revision_status,state,policy_version,prompt_version,response_version,total_questions,completed_at) values (?,?,?,?,1,'Course',?,'DRAFT','FAILED','1','1','1',1,current_timestamp)",submission,course,revision,owner,"a".repeat(64));
        jdbc.update("insert into user_notifications(id,user_id,type,course_id,revision_id,submission_id,dedupe_key,message) values (?,?,'COURSE_SUBMISSION_FAILED',?,?,?,'resource','Historical message')",id,owner,course,revision,submission);
        var available=notifications.list(owner,20,false,null).content().getFirst();
        org.assertj.core.api.Assertions.assertThat(available.resourceAvailable()).isTrue();
        org.assertj.core.api.Assertions.assertThat(available.actionUrl()).isEqualTo("/teacher/content/courses/"+course);
        jdbc.update("insert into user_notifications(id,user_id,type,course_id,revision_id,submission_id,dedupe_key,message) values (?,?,'COURSE_SUBMISSION_FAILED',?,?,?,'resource-second','Second')",UUID.randomUUID(),owner,course,revision,submission);
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{ entered.countDown(); if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Resource snapshot release timeout"); return invocation.callRealMethod(); })
            .when(cursors).encode(org.mockito.ArgumentMatchers.eq("notifications"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyMap());
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var snapshot=pool.submit(()->notifications.list(owner,1,false,null));
            try {
                org.assertj.core.api.Assertions.assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        switch(removed) {
            case "course" -> jdbc.update("delete from courses where id=?",course);
            case "revision" -> jdbc.update("delete from course_revisions where id=?",revision);
            case "submission" -> jdbc.update("delete from course_submission_attempts where id=?",submission);
            case "owner" -> jdbc.update("update courses set teacher_id=? where id=?",other,course);
        }
            } finally { release.countDown(); }
            var page=snapshot.get(5,java.util.concurrent.TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(page.content().getFirst().resourceAvailable()).isTrue();
            org.assertj.core.api.Assertions.assertThat(page.content().getFirst().actionUrl()).isEqualTo("/teacher/content/courses/"+course);
            org.assertj.core.api.Assertions.assertThat(page.unreadCount()).isEqualTo(2);
        }
        org.mockito.Mockito.reset(cursors);
        var unavailable=notifications.list(owner,20,false,null).content().getFirst();
        org.assertj.core.api.Assertions.assertThat(unavailable.resourceAvailable()).isFalse();
        org.assertj.core.api.Assertions.assertThat(unavailable.actionUrl()).isNull();
        org.assertj.core.api.Assertions.assertThat(unavailable.courseId()).isEqualTo(course);
        org.assertj.core.api.Assertions.assertThat(unavailable.revisionId()).isEqualTo(revision);
        org.assertj.core.api.Assertions.assertThat(unavailable.submissionId()).isEqualTo(submission);
        var actor=authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(users.findById(owner).orElseThrow(),null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER"))));
        var result=mvc.perform(get("/api/notifications").with(actor)).andExpect(status().isOk()).andReturn();
        var json=new tools.jackson.databind.json.JsonMapper().readTree(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(json.propertyNames()).containsExactlyInAnyOrder("content","nextCursor","hasNext","size","unreadCount");
        org.assertj.core.api.Assertions.assertThat(json.get("content").get(0).propertyNames()).containsExactlyInAnyOrder("id","type","message","createdAt","readAt","courseId","revisionId","submissionId","resourceAvailable","actionUrl");
        var read=notifications.read(owner,id);
        org.assertj.core.api.Assertions.assertThat(read.unreadCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(notifications.read(owner,id).readAt()).isEqualTo(read.readAt());
        org.assertj.core.api.Assertions.assertThat(notifications.list(other,20,false,null).content()).isEmpty();
    }

    @Test void globalCountOwnerReadAndUnavailableHistory() throws Exception {
        long owner=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('notify@test.test','test','Teacher','TEACHER') returning id",Long.class);
        long other=jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('other@test.test','test','Other','TEACHER') returning id",Long.class);
        UUID id=UUID.randomUUID();
        for(int i=0;i<3;i++) jdbc.update("insert into user_notifications(id,user_id,type,dedupe_key,message,course_id) values (?,?,'COURSE_SUBMISSION_FAILED',?,'Historical message',999)",i==0?id:UUID.randomUUID(),owner,"test:"+i);
        var actor=authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(users.findById(owner).orElseThrow(),null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER"))));
        mvc.perform(get("/api/notifications?size=1").with(actor)).andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.unreadCount").value(3))
            .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.content[0].resourceAvailable").value(false));
        mvc.perform(get("/api/notifications/unread-count").with(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(3));
        mvc.perform(patch("/api/notifications/{id}/read",id).with(authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(users.findById(other).orElseThrow(),null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER")))))).andExpect(status().isNotFound());
        for(int i=0;i<2;i++) mvc.perform(patch("/api/notifications/{id}/read",id).with(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(2)).andExpect(jsonPath("$.readAt").isNotEmpty());
        mvc.perform(get("/api/notifications?unreadOnly=invalid").with(actor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/notifications?cursor=invalid").with(actor)).andExpect(status().isBadRequest());
    }
}

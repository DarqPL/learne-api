package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.maintenance.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.jar.JarFile;

@Timeout(20)
class JobRetryCommandIT extends JobsPostgresTestSupport {
    private JobRetryCommand command() {
        var mapper=JsonMapper.builder().build();
        return new JobRetryCommand(jdbc,manager,new MailPayloadCodec(mapper),
                new MailEligibilityService(jdbc,manager,new BCryptPasswordEncoder(),Clock.systemUTC()),mapper,"test-cloud");
    }
    private UUID notice() {
        long user=jdbc.queryForObject("INSERT INTO users(email,password_hash,full_name,role) VALUES('retry@example.test','test','Retry','STUDENT') RETURNING id",Long.class);
        long order=jdbc.queryForObject("""
                INSERT INTO orders(user_id,order_type,status,original_amount_vnd,final_amount_vnd,payment_provider,expires_at)
                VALUES(?,'COURSE_PURCHASE','PAID',100,100,'INTERNAL',clock_timestamp()+interval '1 day') RETURNING id
                """,Long.class,user);
        var mapper=JsonMapper.builder().build();
        var payload=new MailPayload(user,"retry@example.test","Retry",MailTemplate.PAYMENT_CONFIRMATION,null,null,
                null,null,null,null,order,Map.of("amountVnd","100","itemDescription","Course","paidAt",Instant.now().toString()));
        return job("PAYMENT_CONFIRMATION_MAIL","MAIL_NOTICE",new MailPayloadCodec(mapper).encode(payload));
    }
    private UUID job(String type,String queue,String payload) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO background_jobs(id,queue,type,payload_version,dedupe_key,payload,max_attempts,state,attempts,completed_at)
                VALUES(?,?,?,1,?,?::jsonb,3,'DEAD',3,clock_timestamp())
                """,id,queue,type,id.toString(),payload);
        return id;
    }
    @Test void validNoticeResetsSameEnvelopeAndCanBeLeasedOnlyOnce() {
        UUID id=notice(); var before=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
        command().retry(id,"INCIDENT_123","operator.one");
        var after=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
        for(String field:List.of("id","dedupe_key","payload","type","payload_version","created_at","max_attempts"))
            assertThat(after.get(field)).isEqualTo(before.get(field));
        assertThat(after).containsEntry("state","READY").containsEntry("attempts",0).containsEntry("completed_at",null);
        assertThat(store.claim(QueueName.MAIL_NOTICE,Map.of(JobType.PAYMENT_CONFIRMATION_MAIL,Set.of(1)),Duration.ofSeconds(30))).isPresent();
        assertThat(store.claim(QueueName.MAIL_NOTICE,Map.of(JobType.PAYMENT_CONFIRMATION_MAIL,Set.of(1)),Duration.ofSeconds(30))).isEmpty();
    }
    @Test void changedEmailRejectedWithoutMutation() {
        UUID id=notice(); jdbc.update("UPDATE users SET email='changed@example.test'");
        var before=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
        assertThatThrownBy(()->command().retry(id,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id)).isEqualTo(before);
    }
    @Test void unsupportedActiveMalformedAndUnknownCommandsFailWithoutMutation() {
        UUID id=notice();
        for(String type:List.of("REGISTRATION_OTP_MAIL","PASSWORD_RESET_MAIL","TEACHER_INVITATION_MAIL","COURSE_ENRICHMENT","FUTURE_TYPE")) {
            jdbc.update("UPDATE background_jobs SET type=? WHERE id=?",type,id);
            var before=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
            assertThatThrownBy(()->command().retry(id,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id)).isEqualTo(before);
        }
        jdbc.update("UPDATE background_jobs SET type='PAYMENT_CONFIRMATION_MAIL',payload_version=2 WHERE id=?",id);
        assertThatThrownBy(()->command().retry(id,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE background_jobs SET payload_version=1,state='READY',completed_at=NULL WHERE id=?",id);
        assertThatThrownBy(()->command().retry(id,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        for(String raw:List.of("bad-uuid",UUID.randomUUID().toString())) {
            var cmd=command(); cmd.run(new DefaultApplicationArguments("--app.jobs.maintenance.retry-job="+raw,
                    "--app.jobs.maintenance.reason=INCIDENT_123","--app.jobs.maintenance.operator=operator.one"));
            assertThat(cmd.getExitCode()).isEqualTo(2);
        }
    }
    @Test void concurrentRetriesHaveExactlyOneWinner() throws Exception {
        UUID id=notice(); var start=new CountDownLatch(1); var wins=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Void> attempt=()->{ start.await(); try { command().retry(id,"INCIDENT_123","operator.one"); wins.incrementAndGet(); }
                catch(IllegalArgumentException rejected) { } return null; };
            var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            first.get(8,TimeUnit.SECONDS); second.get(8,TimeUnit.SECONDS);
        }
        assertThat(wins).hasValue(1);
    }
    @Test void actualNonwebContextRejectsUnknownIdWithoutHandlersOrMutation() {
        var app=new SpringApplication(JobMaintenanceApplication.class); app.setWebApplicationType(WebApplicationType.NONE);
        try(var context=app.run("--spring.config.location=optional:classpath:/maintenance-test.properties",
                "--spring.datasource.url="+postgresUrl(),
                "--spring.datasource.username=jobs_test","--spring.datasource.password=isolated-jobs-test-password",
                "--app.jobs.maintenance.retry-job="+UUID.randomUUID(),"--app.jobs.maintenance.reason=INCIDENT_123",
                "--app.jobs.maintenance.operator=operator.one")) {
            assertThat(context.getBeansOfType(com.learne.backend.jobs.service.JobHandler.class)).isEmpty();
            assertThat(SpringApplication.exit(context)).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs",Integer.class)).isZero();
    }
    private String postgresUrl() { return "jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/jobs_it"; }

    private long user(String email,String role) {
        return jdbc.queryForObject("INSERT INTO users(email,password_hash,full_name,role) VALUES(?,'test','Retry',?) RETURNING id",Long.class,email,role);
    }
    private long course(long teacher) {
        return jdbc.queryForObject("INSERT INTO courses(teacher_id,name,type,level,status) VALUES(?,'Course','COMPREHENSIVE','A1','DRAFT') RETURNING id",Long.class,teacher);
    }
    private long revision(long course,long teacher,int number,String status) {
        return jdbc.queryForObject("INSERT INTO course_revisions(course_id,teacher_id,revision_number,status,name,type,level) VALUES(?,?,?,?,'Course','COMPREHENSIVE','A1') RETURNING id",Long.class,course,teacher,number,status);
    }
    private UUID mail(MailPayload payload) {
        return job(payload.template().jobType().name(),"MAIL_NOTICE",new MailPayloadCodec(JsonMapper.builder().build()).encode(payload));
    }
    private void rejectsUnchanged(UUID id) {
        var before=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
        assertThatThrownBy(()->command().retry(id,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id)).isEqualTo(before);
    }
    @Test void pendingReviewRequiresActiveAdminAndCurrentLatestPendingRevision() {
        long teacher=user("teacher@example.test","TEACHER"); long admin=user("admin@example.test","ADMIN");
        long course=course(teacher); long revision=revision(course,teacher,1,"PENDING_REVIEW");
        var payload=new MailPayload(admin,"admin@example.test","Admin",MailTemplate.COURSE_PENDING_REVIEW,null,null,
                course,revision,UUID.randomUUID(),null,null,Map.of("courseName","Course","teacherName","Teacher","submittedAt",Instant.now().toString()));
        UUID valid=mail(payload);
        // A locked admin remains eligible under the production notice rule.
        jdbc.update("UPDATE users SET is_locked=true WHERE id=?",admin);
        command().retry(valid,"INCIDENT_123","operator.one");
        assertThat(jdbc.queryForObject("SELECT state FROM background_jobs WHERE id=?",String.class,valid)).isEqualTo("READY");
        UUID negative=mail(payload);
        jdbc.update("UPDATE users SET is_active=false WHERE id=?",admin); rejectsUnchanged(negative);
        jdbc.update("UPDATE users SET is_active=true,role='STUDENT' WHERE id=?",admin); rejectsUnchanged(negative);
        jdbc.update("UPDATE users SET role='ADMIN',email='changed@example.test' WHERE id=?",admin); rejectsUnchanged(negative);
        jdbc.update("UPDATE users SET email='admin@example.test' WHERE id=?",admin);
        jdbc.update("UPDATE course_revisions SET status='REJECTED' WHERE id=?",revision); rejectsUnchanged(negative);
        jdbc.update("UPDATE course_revisions SET status='PENDING_REVIEW' WHERE id=?",revision);
        revision(course,teacher,2,"DRAFT"); rejectsUnchanged(negative);
    }
    @Test void historicalReviewUsesImmutableDecisionAfterCourseDeletionAndRejectsMismatchedHistory() {
        long teacher=user("teacher@example.test","TEACHER"); long course=course(teacher);
        long revision=revision(course,teacher,1,"REJECTED"); UUID submission=UUID.randomUUID(),decision=UUID.randomUUID();
        Instant reviewed=Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO course_review_decisions(id,revision_id,course_id,submission_id,reviewer_id,teacher_id,
                    decision,course_name,recipient_email,rejection_reason,reviewed_at)
                VALUES(?,?,?,?,1,?,'REJECTED','Course','teacher@example.test','Private review reason',?)
                """,decision,revision,course,submission,teacher,java.sql.Timestamp.from(reviewed));
        var payload=new MailPayload(teacher,"teacher@example.test","Teacher",MailTemplate.COURSE_REVIEW_RESULT,null,null,
                course,revision,submission,decision,null,Map.of("courseName","Course","decision","REJECTED",
                "rejectionReason","Private review reason","reviewedAt",reviewed.toString()));
        UUID valid=mail(payload);
        jdbc.update("DELETE FROM course_revisions WHERE id=?",revision); jdbc.update("DELETE FROM courses WHERE id=?",course);
        command().retry(valid,"INCIDENT_123","operator.one");
        assertThat(jdbc.queryForObject("SELECT state FROM background_jobs WHERE id=?",String.class,valid)).isEqualTo("READY");
        UUID negative=mail(payload);
        jdbc.update("UPDATE users SET email='changed@example.test' WHERE id=?",teacher); rejectsUnchanged(negative);
        jdbc.update("UPDATE users SET email='teacher@example.test',role='ADMIN' WHERE id=?",teacher); rejectsUnchanged(negative);
        jdbc.update("UPDATE users SET role='TEACHER' WHERE id=?",teacher);
        for(String replacement:List.of("\"Course\"","\"Private review reason\"","\"2026-01-01T00:00:00Z\"")) {
            String original=jdbc.queryForObject("SELECT payload::text FROM background_jobs WHERE id=?",String.class,negative);
            String changed=original.replace(replacement,replacement.equals("\"2026-01-01T00:00:00Z\"")?"\"2026-01-02T00:00:00Z\"":"\"Tampered\"");
            jdbc.update("UPDATE background_jobs SET payload=?::jsonb WHERE id=?",changed,negative); rejectsUnchanged(negative);
            jdbc.update("UPDATE background_jobs SET payload=?::jsonb WHERE id=?",original,negative);
        }
        jdbc.update("UPDATE background_jobs SET payload=jsonb_set(payload,'{submissionId}',to_jsonb(?::text)) WHERE id=?",UUID.randomUUID().toString(),negative);
        rejectsUnchanged(negative);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM course_review_decisions WHERE id=?",Integer.class,decision)).isOne();
    }

    private int context(String... options) {
        var args=new ArrayList<>(List.of("--spring.config.location=optional:classpath:/maintenance-test.properties",
                "--spring.datasource.url="+postgresUrl(),"--spring.datasource.username=jobs_test",
                "--spring.datasource.password=isolated-jobs-test-password","--spring.main.banner-mode=off"));
        args.addAll(List.of(options));
        var app=new SpringApplication(JobMaintenanceApplication.class); app.setWebApplicationType(WebApplicationType.NONE);
        try(var context=app.run(args.toArray(String[]::new))) {
            assertThat(Arrays.stream(context.getBeanDefinitionNames()).filter(n->!n.startsWith("org.springframework.")))
                    .containsExactlyInAnyOrder("jobMaintenanceApplication","maintenanceDataSource","maintenanceJdbc","maintenanceTransactions","retryCommand");
            assertThat(context).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
            assertThat(context.getEnvironment().getProperty("local.server.port")).isNull();
            return SpringApplication.exit(context);
        }
    }
    @Test void malformedActualContextsRejectWithoutMutationAndOnlySafeAuditIsEmitted() {
        UUID id=notice(); var before=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
        Logger logger=(Logger)LoggerFactory.getLogger(JobRetryCommand.class);
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        var previous=logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            var base=List.of("--app.jobs.maintenance.retry-job="+id,"--app.jobs.maintenance.reason=INCIDENT_123","--app.jobs.maintenance.operator=operator.one");
            for(String extra:List.of("--app.jobs.maintenance.retry-job="+id,"--app.jobs.maintenance.reason=SECOND_REASON",
                    "--app.jobs.maintenance.operator=other","--app.jobs.maintenance.unknown=unexpected","unexpected-positional")) {
                var args=new ArrayList<>(base); args.add(extra);
                assertThat(context(args.toArray(String[]::new))).isEqualTo(2);
                assertThat(jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id)).isEqualTo(before);
            }
            assertThat(context("--app.jobs.maintenance.retry-job=malformed","--app.jobs.maintenance.reason=INCIDENT_123",
                    "--app.jobs.maintenance.operator=operator.one")).isEqualTo(2);
            assertThat(context("--app.jobs.maintenance.retry-job="+id,"--app.jobs.maintenance.operator=operator.one")).isEqualTo(2);
            for(String bad:List.of("recipient@example.test","SECRET\nINJECTED","", "A".repeat(65))) {
                assertThat(context("--app.jobs.maintenance.retry-job="+id,"--app.jobs.maintenance.reason="+bad,
                        "--app.jobs.maintenance.operator=operator.one")).isEqualTo(2);
                assertThat(context("--app.jobs.maintenance.retry-job="+id,"--app.jobs.maintenance.reason=INCIDENT_123",
                        "--app.jobs.maintenance.operator="+bad)).isEqualTo(2);
                assertThat(jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id)).isEqualTo(before);
            }
            UUID broken=job("PAYMENT_CONFIRMATION_MAIL","MAIL_NOTICE","{\"sensitive\":\"PAYLOAD_SENTINEL\"}");
            assertThat(context("--app.jobs.maintenance.retry-job="+broken,"--app.jobs.maintenance.reason=INCIDENT_123",
                    "--app.jobs.maintenance.operator=operator.one")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT state FROM background_jobs WHERE id=?",String.class,broken)).isEqualTo("DEAD");
            // SpringApplication reinitializes logging for each actual context above.
            logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.INFO); appender.start();
            var rejected=command();
            rejected.run(new DefaultApplicationArguments("--app.jobs.maintenance.retry-job="+broken,
                    "--app.jobs.maintenance.reason=INCIDENT_123","--app.jobs.maintenance.operator=operator.one"));
            assertThat(rejected.getExitCode()).isEqualTo(2);
            command().retry(id,"INCIDENT_123","operator.one");
            var messages=appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).filteredOn(m->m.startsWith("JOB_RETRY job=")).containsExactly(
                    "JOB_RETRY job="+id+" type=PAYMENT_CONFIRMATION_MAIL operator=operator.one reason=INCIDENT_123");
            assertThat(String.join("\n",messages)).doesNotContain("PAYLOAD_SENTINEL","retry@example.test","recipientEmail","isolated-jobs-test-password");
            assertThat(appender.list).allSatisfy(event->assertThat(event.getThrowableProxy()).isNull());
        } finally { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }

    @Test @Timeout(60) void packagedPowerShellWrapperSucceedsAndRejectsWithoutMutationInIsolatedEnvironment(
            @org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        UUID id=notice(); var before=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
        Path artifact=Path.of("target/backend-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        try(var jar=new JarFile(artifact.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Start-Class")).isEqualTo("com.learne.backend.BackendApplication");
        }
        var output=wrapper(id,temp.resolve("success.log")); assertThat(output.exit()).isZero();
        assertThat(output.text()).contains("JobMaintenanceApplication","JOB_RETRY job="+id)
                .doesNotContain("Tomcat","Netty","SecurityConfig","HikariPool","DispatcherServlet","PAYLOAD_SENTINEL","retry@example.test");
        var after=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id);
        assertThat(after).containsEntry("state","READY").containsEntry("attempts",0);
        for(String field:List.of("id","dedupe_key","payload","type","payload_version","created_at"))
            assertThat(after.get(field)).isEqualTo(before.get(field));
        assertThat(wrapper(id,temp.resolve("active.log")).exit()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",id)).isEqualTo(after);
        UUID malformed=job("PAYMENT_CONFIRMATION_MAIL","MAIL_NOTICE","{\"sensitive\":\"PAYLOAD_SENTINEL\"}");
        var invalidBefore=jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",malformed);
        var invalid=wrapper(malformed,temp.resolve("invalid.log")); assertThat(invalid.exit()).isEqualTo(2);
        assertThat(invalid.text()).doesNotContain("PAYLOAD_SENTINEL","isolated-jobs-test-password");
        assertThat(jdbc.queryForMap("SELECT * FROM background_jobs WHERE id=?",malformed)).isEqualTo(invalidBefore);
        assertThat(wrapper(UUID.randomUUID(),temp.resolve("unknown.log")).exit()).isEqualTo(2);
    }
    private record ProcessResult(int exit,String text) {}
    private ProcessResult wrapper(UUID id,Path output) throws Exception {
        String windows=System.getenv("SystemRoot");
        var process=new ProcessBuilder(windows+"\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",
                Path.of("scripts/retry-background-job.ps1").toAbsolutePath().toString(),"-JobId",id.toString(),
                "-Reason","INCIDENT_123","-Operator","operator.one","-ArtifactPath",
                Path.of("target/backend-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"-JavaPath",
                Path.of(System.getProperty("java.home"),"bin","java.exe").toString());
        // Whitelist OS bootstrap plus isolated fixture settings; inherit no application environment.
        process.environment().clear();
        // Windows resolves shared cache locations through these OS variables. Leaving them unset
        // can turn an unexpanded %SystemDrive% path into a directory under the working directory.
        for(String name:List.of("SystemDrive","ProgramData","ALLUSERSPROFILE",
                "PATH","PATHEXT","COMSPEC","USERPROFILE","APPDATA","LOCALAPPDATA",
                "HOMEDRIVE","HOMEPATH","PSModulePath","PROCESSOR_ARCHITECTURE","NUMBER_OF_PROCESSORS")) {
            String value=System.getenv(name); if(value!=null) process.environment().put(name,value);
        }
        process.environment().putAll(Map.of("SystemRoot",windows,"WINDIR",windows,
                "TEMP",output.getParent().toString(),"TMP",output.getParent().toString(),
                "SPRING_DATASOURCE_URL",postgresUrl(),"SPRING_DATASOURCE_USERNAME","jobs_test",
                "SPRING_DATASOURCE_PASSWORD","isolated-jobs-test-password"));
        process.redirectErrorStream(true).redirectOutput(output.toFile());
        var child=process.start();
        try {
            assertThat(child.waitFor(12,TimeUnit.SECONDS)).as("bounded nonweb process exits").isTrue();
            assertThat(Path.of("%SystemDrive%")).as("Windows cache paths must not escape into the checkout").doesNotExist();
            return new ProcessResult(child.exitValue(),Files.readString(output));
        } finally { if(child.isAlive()) child.destroyForcibly(); }
    }

    @Test void mediaRequiresCurrentCourseManagedProvenanceAndGlobalAbsence() {
        long user=jdbc.queryForObject("INSERT INTO users(email,password_hash,full_name,role) VALUES('media-retry@example.test','test','Retry','TEACHER') RETURNING id",Long.class);
        long course=jdbc.queryForObject("INSERT INTO courses(teacher_id,name,type,level,status) VALUES(?,'Course','COMPREHENSIVE','A1','DRAFT') RETURNING id",Long.class,user);
        String payload="{\"courseId\":"+course+",\"publicId\":\"learne/course-audio/retry\",\"url\":\"https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/retry.mp3\",\"resourceType\":\"VIDEO\"}";
        UUID safe=job("MEDIA_CLEANUP","MEDIA",payload);
        command().retry(safe,"INCIDENT_123","operator.one");
        assertThat(jdbc.queryForObject("SELECT state FROM background_jobs WHERE id=?",String.class,safe)).isEqualTo("READY");
        long revision=jdbc.queryForObject("INSERT INTO course_revisions(course_id,teacher_id,revision_number,status,name,type,level) VALUES(?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') RETURNING id",Long.class,course,user);
        long topic=jdbc.queryForObject("INSERT INTO topic_revisions(revision_id,name) VALUES(?,'Topic') RETURNING id",Long.class,revision);
        jdbc.update("INSERT INTO lesson_revisions(topic_revision_id,teacher_id,title,skill_type,audio_public_id) VALUES(?,?,'Lesson','READING','learne/course-audio/retry')",topic,user);
        UUID referenced=job("MEDIA_CLEANUP","MEDIA",payload);
        assertThatThrownBy(()->command().retry(referenced,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE lesson_revisions SET audio_public_id=NULL,audio_url='https://example.test/%252F'");
        assertThatThrownBy(()->command().retry(referenced,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE lesson_revisions SET audio_url=NULL");
        UUID unproven=job("MEDIA_CLEANUP","MEDIA",payload.replace("res.cloudinary.com","example.test"));
        assertThatThrownBy(()->command().retry(unproven,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        UUID missing=job("MEDIA_CLEANUP","MEDIA",payload.replace("\"courseId\":"+course+",","\"courseId\":99999999,"));
        assertThatThrownBy(()->command().retry(missing,"INCIDENT_123","operator.one")).isInstanceOf(IllegalArgumentException.class);
        for(UUID id:List.of(referenced,unproven,missing))
            assertThat(jdbc.queryForObject("SELECT state FROM background_jobs WHERE id=?",String.class,id)).isEqualTo("DEAD");
    }

    @Test void mediaWaitsForCourseBeforeJobLockAndReadsReferencesAfterWriterCommits() throws Exception {
        long user=jdbc.queryForObject("INSERT INTO users(email,password_hash,full_name,role) VALUES('locked-retry@example.test','test','Retry','TEACHER') RETURNING id",Long.class);
        long course=jdbc.queryForObject("INSERT INTO courses(teacher_id,name,type,level,status) VALUES(?,'Course','COMPREHENSIVE','A1','DRAFT') RETURNING id",Long.class,user);
        long revision=jdbc.queryForObject("INSERT INTO course_revisions(course_id,teacher_id,revision_number,status,name,type,level) VALUES(?,?,1,'DRAFT','Course','COMPREHENSIVE','A1') RETURNING id",Long.class,course,user);
        long topic=jdbc.queryForObject("INSERT INTO topic_revisions(revision_id,name) VALUES(?,'Topic') RETURNING id",Long.class,revision);
        UUID id=job("MEDIA_CLEANUP","MEDIA","{\"courseId\":"+course+",\"publicId\":\"learne/course-audio/retry\",\"url\":\"https://res.cloudinary.com/test-cloud/video/upload/learne/course-audio/retry.mp3\",\"resourceType\":\"VIDEO\"}");
        try(var writer=dataSource.getConnection(); var pool=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try(var statement=writer.prepareStatement("SELECT id FROM courses WHERE id=? FOR UPDATE")) {
                statement.setLong(1,course); statement.executeQuery().close();
            }
            var retry=pool.submit(()->command().retry(id,"INCIDENT_123","operator.one"));
            // Observe the real lock wait, rather than sleeping or assuming thread scheduling.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM courses WHERE%')",Boolean.class));
                if(waiting) break;
                Thread.yield();
            }
            assertThat(waiting).isTrue();
            try(var statement=writer.prepareStatement("SELECT id FROM background_jobs WHERE id=? FOR UPDATE NOWAIT")) {
                statement.setObject(1,id); statement.executeQuery().close();
            }
            try(var statement=writer.prepareStatement("INSERT INTO lesson_revisions(topic_revision_id,teacher_id,title,skill_type,audio_public_id) VALUES(?,?,'Lesson','READING','learne/course-audio/retry')")) {
                statement.setLong(1,topic); statement.setLong(2,user); statement.executeUpdate();
            }
            writer.commit();
            assertThatThrownBy(()->retry.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT state FROM background_jobs WHERE id=?",String.class,id)).isEqualTo("DEAD");
    }
}

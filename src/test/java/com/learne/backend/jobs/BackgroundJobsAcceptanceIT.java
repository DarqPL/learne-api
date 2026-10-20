package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.config.JobProperties;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.*;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.enrichment.*;
import com.learne.backend.teacher.content.submission.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import com.learne.backend.cache.CacheInvalidationService;
import io.lettuce.core.RedisClient;
import org.testcontainers.containers.GenericContainer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.*;

/** R4: real durable producers/domain handlers and dispatcher; only provider ports are fake. */
@Import(BackgroundJobsAcceptanceIT.Providers.class)
@TestPropertySource(properties={"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1", "app.jobs.course-submission-enabled=true",
        "app.jobs.media-cleanup-enabled=false", "app.cloudinary.cloud-name=test-cloud"})
@Timeout(90)
class BackgroundJobsAcceptanceIT extends JobsPostgresTestSupport {
    static final AtomicLong sqlExecutions=new AtomicLong();
    static final AtomicLong sqlQueries=new AtomicLong(),sqlUpdates=new AtomicLong(),sqlBatches=new AtomicLong();
    static final GenericContainer<?> redis=new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379).withCommand("redis-server","--maxmemory","32mb","--maxmemory-policy","noeviction")
            .withCreateContainerCmdModifier(c->c.getHostConfig().withMemory(64L*1024*1024));
    @BeforeAll static void redisStart() { redis.start(); }
    @AfterAll static void redisStop() { redis.stop(); }
    static Object wrapJdbc(Object target,Class<?> api) {
        return Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->{
            try {
                if(method.getName().startsWith("execute")) sqlExecutions.incrementAndGet();
                if(method.getName().equals("executeQuery")) sqlQueries.incrementAndGet();
                if(method.getName().equals("executeUpdate")) sqlUpdates.incrementAndGet();
                if(method.getName().equals("executeBatch")) sqlBatches.incrementAndGet();
                Object result=method.invoke(target,args);
                if(result instanceof java.sql.CallableStatement) return wrapJdbc(result,java.sql.CallableStatement.class);
                if(result instanceof java.sql.PreparedStatement) return wrapJdbc(result,java.sql.PreparedStatement.class);
                if(result instanceof java.sql.Statement) return wrapJdbc(result,java.sql.Statement.class);
                return result;
            } catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
    }
    static class FakeProviders implements CourseKnowledgePointClient, MailTransport, MediaDeletePort {
        volatile CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(0);
        final AtomicInteger calls=new AtomicInteger();
        final List<SimpleMailMessage> accepted=new CopyOnWriteArrayList<>();
        final List<String> deleted=new CopyOnWriteArrayList<>();
        volatile boolean loseAck;
        volatile java.util.function.Consumer<SimpleMailMessage> onSend=message->{};
        public CourseKnowledgePointResponse enrichKnowledgePoints(CourseKnowledgePointRequest request,EnrichmentContract contract) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(contract).isEqualTo(EnrichmentContract.V1);
            assertThat(request.questions()).hasSizeBetween(1,25);
            calls.incrementAndGet(); entered.countDown();
            try { assertThat(release.await(30,TimeUnit.SECONDS)).isTrue(); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return new CourseKnowledgePointResponse(request.questions().stream()
                    .map(q->new CourseKnowledgePointResponse.Item(q.questionId(),"Present Simple")).toList());
        }
        public void send(SimpleMailMessage message) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            onSend.accept(message);
            accepted.add(new SimpleMailMessage(message));
            if(loseAck) { loseAck=false; throw new RetryableJobException("BUSY",null); }
        }
        public void delete(String id) { deleted.add(id); }
        void reset() { calls.set(0); accepted.clear(); deleted.clear(); loseAck=false; onSend=message->{}; entered=new CountDownLatch(1); release=new CountDownLatch(0); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Providers {
        @Bean static BeanPostProcessor countJdbcExecutions() {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof HikariDataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        public java.sql.Connection getConnection() throws java.sql.SQLException {
                            return (java.sql.Connection)wrapJdbc(super.getConnection(),java.sql.Connection.class);
                        }
                        public java.sql.Connection getConnection(String user,String password) throws java.sql.SQLException {
                            return (java.sql.Connection)wrapJdbc(super.getConnection(user,password),java.sql.Connection.class);
                        }
                    };
                }
            };
        }
        @Bean @Primary FakeProviders fakeProviders() { return new FakeProviders(); }
        @Bean MediaCleanupHandler acceptanceMediaHandler(@Lazy JobStore store,LessonAudioCleanupDecisionService decisions,
                FakeProviders fake,tools.jackson.databind.ObjectMapper mapper) {
            return new MediaCleanupHandler(store,decisions,fake,mapper);
        }
    }
    @Autowired FakeProviders fake;
    @Autowired JobRegistry registry;
    @Autowired SubmissionAcceptanceService acceptance;
    @Autowired SubmissionCheckpointService checkpoints;
    @Autowired SubmissionRepository submissions;
    @Autowired SubmissionCompletionPort completion;
    @Autowired QuestionDifficultyCalculator difficulty;
    @Autowired QuestionXpPolicy xp;
    @Autowired MailJobProducer mail;
    @Autowired MediaCleanupProducer media;
    @Autowired PasswordEncoder passwords;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired com.learne.backend.teacher.content.repository.CourseRepository courses;
    @Autowired com.learne.backend.teacher.content.repository.CourseRevisionRepository revisions;
    @Autowired com.learne.backend.notification.NotificationRepository notifications;
    @Autowired com.learne.backend.jobs.crypto.JobPayloadCipher cipher;
    @Autowired CacheInvalidationService cache;
    <T> T transactional(T target) {
        var proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (T)proxy.getProxy();
    }
    record Graph(JobStore store,SubmissionRepository repository,SubmissionCheckpointService checkpoints,
                 SubmissionFinalizationService finalizer,CourseSubmissionHandler handler,JobDispatcher runtime,
                 CountDownLatch crashed,java.util.concurrent.atomic.AtomicReference<JobLease> observedLease) implements AutoCloseable {
        public void close() { runtime.close(); }
    }
    Graph reconstruct(String crash,CacheInvalidationService invalidation) {
        var freshJdbc=new JdbcTemplate(dataSource);
        var freshStore=transactional(new JobStore(new NamedParameterJdbcTemplate(dataSource),new JobProperties(),mapper));
        var repository=transactional(new SubmissionRepository(freshJdbc));
        var projection=transactional(new SubmissionProjectionReader(freshJdbc));
        var checkpoint=transactional(new SubmissionCheckpointService(repository,projection,freshJdbc));
        var finalizer=transactional(new SubmissionFinalizationService(freshJdbc,repository,projection,courses,revisions,
                org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(emf),mail,notifications,invalidation,mapper));
        var hook=new SubmissionCheckpointService(repository,projection,freshJdbc) {
            @Override public Turn load(UUID id) { return checkpoint.load(id); }
            @Override public void checkpoint(JobLease lease,Turn turn,List<CourseReviewEnrichmentService.BatchResult> results) {
                if("response".equals(crash)) throw new SimulatedCrash();
                checkpoint.checkpoint(lease,turn,results);
                if("stage".equals(crash)) throw new SimulatedCrash();
            }
        };
        var handler=new CourseSubmissionHandler(hook,new CourseReviewEnrichmentService(fake,new QuestionDifficultyCalculator(),new QuestionXpPolicy()),finalizer,mapper);
        var crashed=new CountDownLatch(1);
        var observedLease=new java.util.concurrent.atomic.AtomicReference<JobLease>();
        var handlers=new ArrayList<JobHandler>(); handlers.add(new JobHandler() {
            public JobType type() { return handler.type(); }
            public int payloadVersion() { return 1; }
            public void execute(JobLease lease) {
                observedLease.set(lease);
                try { if("claim".equals(crash)) throw new SimulatedCrash(); handler.execute(lease); }
                catch(SimulatedCrash e) { crashed.countDown(); throw e; }
            }
            public void failPermanently(JobLease lease,String code) { handler.failPermanently(lease,code); }
        });
        for(var template:MailTemplate.values()) handlers.add(new MailJobHandler(template.jobType(),freshStore,cipher,new MailPayloadCodec(mapper),
                new MailEligibilityService(freshJdbc,manager,passwords,Clock.systemUTC()),new MailTemplateRenderer("https://learne.test",""),fake));
        var properties=new JobProperties(); properties.setWorkerEnabled(false);
        var runtime=new JobDispatcher(freshStore,new JobRegistry(handlers),properties.snapshot(),Clock.systemUTC(),()->0);
        return new Graph(freshStore,repository,checkpoint,finalizer,handler,runtime,crashed,observedLease);
    }
    static class SimulatedCrash extends Error { }
    private final EnumMap<QueueName,AtomicInteger> active=new EnumMap<>(QueueName.class), maximum=new EnumMap<>(QueueName.class);
    @BeforeEach void reset() { fake.reset(); for(var q:QueueName.values()) { active.put(q,new AtomicInteger()); maximum.put(q,new AtomicInteger()); } }
    long user(String email,String role) {
        return jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values (?,?,?,?) returning id",Long.class,email,passwords.encode("fixture-password"),role,role);
    }
    record Fixture(long teacher,long course,long revision,UUID submission) {}
    Fixture fixture(int count) {
        long teacher=user("r4-teacher@example.test","TEACHER");
        long course=jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'R4','COMPREHENSIVE','A1','DRAFT') returning id",Long.class,teacher);
        long revision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','R4','COMPREHENSIVE','A1') returning id",Long.class,course,teacher);
        long topic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        long lesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,content) values (?,?,'Lesson','READING','Content') returning id",Long.class,topic,teacher);
        jdbc.update("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text,order_index) select ?,?,'MULTIPLE_CHOICE','Question '||n,n from generate_series(1,?) n",lesson,teacher,count);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(acceptance)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // Fixture preparation is complete; include the transactional proxy's commit and return.
        long acceptanceStarted=System.nanoTime();
        var accepted=acceptance.accept(teacher,course);
        long acceptanceElapsedNanos=System.nanoTime()-acceptanceStarted;
        assertThat(acceptanceElapsedNanos).isPositive();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        if(count==1000) System.out.printf("R4_ACCEPTANCE questions=1000 elapsedNanos=%d elapsedMs=%.3f%n",
                acceptanceElapsedNanos,acceptanceElapsedNanos/1_000_000.0);
        return new Fixture(teacher,course,revision,accepted.id());
    }
    JobDispatcher runtime() {
        var handlers=new ArrayList<JobHandler>();
        for(var queue:QueueName.values()) for(var entry:registry.supported(queue).entrySet()) for(int version:entry.getValue()) {
            var delegate=registry.find(entry.getKey(),version).orElseThrow();
            handlers.add(new JobHandler() {
                public JobType type() { return delegate.type(); }
                public int payloadVersion() { return delegate.payloadVersion(); }
                public void execute(JobLease lease) {
                    int n=active.get(queue).incrementAndGet(); maximum.get(queue).accumulateAndGet(n,Math::max);
                    try { delegate.execute(lease); } finally { active.get(queue).decrementAndGet(); }
                }
                public void failPermanently(JobLease lease,String code) { delegate.failPermanently(lease,code); }
            });
        }
        var p=new JobProperties(); p.setWorkerEnabled(false);
        p.getLanes().get(QueueName.AI).setHeartbeat(Duration.ofMillis(100));
        for(var lane:p.getLanes().values()) lane.setPoll(Duration.ofMillis(50));
        return new JobDispatcher(store,new JobRegistry(handlers),p.snapshot(),Clock.systemUTC(),()->0);
    }
    void await(BooleanSupplier condition) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>until) fail("R4 condition deadline; jobs=%s",jdbc.queryForList("select type,state,last_error_code from background_jobs"));
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }
    int count(String sql) { return jdbc.queryForObject(sql,Integer.class); }
    String state(UUID id) { return jdbc.queryForObject("select state from background_jobs where id=?",String.class,id); }
    JobLease claim() { return store.claim(QueueName.AI,registry.supported(QueueName.AI),Duration.ofSeconds(30)).orElseThrow(); }
    void expire(JobLease lease) {
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where id=?",lease.jobId());
        assertThat(store.recoverExpired(10)).isOne();
    }
    UUID payment() {
        long student=user("r4-student@example.test","STUDENT");
        long order=jdbc.queryForObject("insert into orders(user_id,order_type,status,original_amount_vnd,final_amount_vnd,payment_provider,expires_at) values (?,'COURSE_PURCHASE','PAID',100,100,'INTERNAL',clock_timestamp()) returning id",Long.class,student);
        return enqueue(new MailPayload(student,"r4-student@example.test","Student",MailTemplate.PAYMENT_CONFIRMATION,null,null,null,null,null,null,order,
                Map.of("amountVnd","100","itemDescription","R4","paidAt",Instant.now().toString())));
    }
    UUID enqueue(MailPayload payload) {
        return new TransactionTemplate(manager).execute(s->mail.enqueue(payload.template().jobType(),"r4:"+payload.template(),payload,
                MailPayloadCodec.requiresSecret(payload.template())?"fixture-password".getBytes(java.nio.charset.StandardCharsets.UTF_8):null));
    }
    @Test void thousandQuestionsKeepOtherLanesIndependentAndFinishFortyRealBatches() throws Exception {
        long started=System.nanoTime(), sqlBefore=sqlExecutions.get(), heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long queriesBefore=sqlQueries.get(), updatesBefore=sqlUpdates.get(), batchesBefore=sqlBatches.get();
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(896L*1024*1024);
        var f=fixture(1000); user("r4-admin@example.test","ADMIN");
        fake.onSend=message->{ if(Arrays.asList(message.getTo()).contains("r4-admin@example.test")) {
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,f.revision())).isEqualTo("PENDING_REVIEW");
            assertThat(submissions.parent(f.submission()).state()).isEqualTo("SUCCEEDED");
        }};
        fake.release=new CountDownLatch(1);
        var runtime=runtime();
        try {
            assertThat(runtime.dispatch(QueueName.AI)).isTrue(); assertThat(fake.entered.await(5,TimeUnit.SECONDS)).isTrue();
            UUID generation=UUID.randomUUID(); Instant expiry=Instant.now().plusSeconds(3600);
            jdbc.update("insert into registration_otps(email,full_name,password_hash,otp_hash,expires_at,mail_generation) values ('r4-otp@example.test','OTP','hash','hash',?,?)",java.sql.Timestamp.from(expiry),generation);
            enqueue(new MailPayload(0,"r4-otp@example.test","OTP",MailTemplate.REGISTRATION_OTP,generation.toString(),expiry,null,null,null,null,null,Map.of()));
            long reset=user("r4-reset@example.test","STUDENT");
            long token=jdbc.queryForObject("insert into password_reset_tokens(user_id,token_hash,expires_at) values (?,'r4-token',?) returning id",Long.class,reset,java.sql.Timestamp.from(expiry));
            enqueue(new MailPayload(reset,"r4-reset@example.test","Reset",MailTemplate.PASSWORD_RESET,""+token,expiry,null,null,null,null,null,Map.of()));
            long invited=user("r4-invite@example.test","TEACHER"); UUID invite=UUID.randomUUID();
            jdbc.update("update users set invitation_generation=?,must_change_password=true where id=?",invite,invited);
            enqueue(new MailPayload(invited,"r4-invite@example.test","Invite",MailTemplate.TEACHER_INVITATION,invite.toString(),expiry,null,null,null,null,null,Map.of()));
            payment();
            new TransactionTemplate(manager).executeWithoutResult(s->media.enqueue(UUID.randomUUID(),f.course(),List.of(new LessonAudioState(
                    "https://res.cloudinary.com/test-cloud/video/upload/v123/learne/course-audio/r4.mp3","learne/course-audio/r4"))));
            await(()->{ for(var q:List.of(QueueName.MAIL_AUTH,QueueName.MAIL_NOTICE,QueueName.MEDIA)) runtime.dispatch(q);
                return count("select count(*) from background_jobs where type<>'COURSE_ENRICHMENT' and state='SUCCEEDED'")==5; });
            assertThat(fake.calls).hasValue(1); assertThat(fake.deleted).containsExactly("learne/course-audio/r4");
            assertThat(count("select count(*) from background_jobs where type='COURSE_PENDING_REVIEW_MAIL'")).isZero();
            var reader=new SubmissionQueryService(jdbc,submissions);
            assertThat(reader.get(f.teacher(),f.course(),f.submission()).status()).isIn("QUEUED","PROCESSING");
            // Disconnect the polling client by discarding it; worker state belongs exclusively to SQL.
            reader=null;
            for(int i=0;i<2000;i++) assertThat(runtime.dispatch(QueueName.AI)).isFalse();
            var pool=dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            long heapDuring=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
            assertThat(pool.getTotalConnections()).isLessThanOrEqualTo(4);
            assertThat(fake.calls).hasValue(1);
            assertExecutorBounds(runtime);
            runtime.start();
            for(int window=1;window<=3;window++) {
                for(int renewal=0;renewal<10;renewal++) {
                    var before=jdbc.queryForObject("select lease_until from background_jobs where type='COURSE_ENRICHMENT'",java.sql.Timestamp.class);
                    await(()->jdbc.queryForObject("select lease_until from background_jobs where type='COURSE_ENRICHMENT'",java.sql.Timestamp.class).after(before));
                    assertExecutorBounds(runtime);
                    assertThat(pool.getTotalConnections()).isLessThanOrEqualTo(4);
                }
                long sqlAtWindow=sqlExecutions.get();
                for(int attempt=0;attempt<2000;attempt++) assertThat(runtime.dispatch(QueueName.AI)).isFalse();
                assertThat(fake.calls).hasValue(1); assertExecutorBounds(runtime);
                long threads=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().startsWith("jobs-")).count();
                assertThat(threads).isLessThanOrEqualTo(15);
                assertThat(pool.getTotalConnections()).isLessThanOrEqualTo(4);
                System.out.printf("R4_WINDOW window=%d renewals=10 denied=2000 threads=%d poolActive=%d poolIdle=%d heap=%d admissionJdbcExecutions=%d executors=%s%n",window,threads,pool.getActiveConnections(),pool.getIdleConnections(),Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),sqlExecutions.get()-sqlAtWindow,executorSnapshot(runtime));
            }
            System.out.printf("R4_STALL heapBefore=%d heapDuring=%d poolActive=%d poolIdle=%d jobsThreads=%d deniedAdmissions=2000%n",heapBefore,heapDuring,pool.getActiveConnections(),pool.getIdleConnections(),Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().startsWith("jobs-")).count());
            fake.release.countDown();
            await(()->{runtime.dispatch(QueueName.AI);return "SUCCEEDED".equals(submissions.parent(f.submission()).state());});
            assertThat(fake.calls).hasValue(40);
            assertThat(count("select count(*) from course_submission_questions where knowledge_point is not null")).isEqualTo(1000);
            assertThat(count("select count(distinct question_revision_id) from course_submission_questions")).isEqualTo(1000);
            assertThat(count("select count(*) from question_revisions where knowledge_point='present_simple'")).isEqualTo(1000);
            assertThat(jdbc.queryForObject("select status from course_revisions where id=?",String.class,f.revision())).isEqualTo("PENDING_REVIEW");
            assertThat(new SubmissionQueryService(jdbc,submissions).get(f.teacher(),f.course(),f.submission()).status()).isEqualTo("SUCCEEDED");
            await(()->{runtime.dispatch(QueueName.MAIL_NOTICE);return count("select count(*) from background_jobs where type='COURSE_PENDING_REVIEW_MAIL' and state='SUCCEEDED'")==1;});
            assertThat(count("select count(*) from user_notifications")).isOne();
            maximum.forEach((q,n)->assertThat(n.get()).as("maximum active %s",q).isOne());
            assertThat(count("select count(*) from background_jobs where state<>'SUCCEEDED'")).isZero();
            System.out.printf("R4_COMPLETE elapsedMs=%d maxActive=%s mailAccepted=%d jdbcExecutions=%d jvmMaxHeap=%d%n",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),maximum,fake.accepted.size(),sqlExecutions.get()-sqlBefore,Runtime.getRuntime().maxMemory());
            System.out.printf("R4_SQL queries=%d updates=%d batches=%d%n",sqlQueries.get()-queriesBefore,sqlUpdates.get()-updatesBefore,sqlBatches.get()-batchesBefore);
        } finally { fake.release.countDown(); runtime.close(); }
    }
    void assertExecutorBounds(JobDispatcher runtime) throws Exception {
        var field=JobDispatcher.class.getDeclaredField("lanes"); field.setAccessible(true);
        for(var lane:((Map<?,?>)field.get(runtime)).values()) {
            var executorField=lane.getClass().getDeclaredField("executor"); executorField.setAccessible(true);
            var executor=(ThreadPoolExecutor)executorField.get(lane);
            assertThat(executor.getMaximumPoolSize()).isOne(); assertThat(executor.getQueue()).isEmpty();
            assertThat(executor.getActiveCount()).isLessThanOrEqualTo(1);
        }
        for(String name:List.of("pollIo","heartbeats","polls","controls")) {
            var control=JobDispatcher.class.getDeclaredField(name); control.setAccessible(true);
            var executor=(ThreadPoolExecutor)control.get(runtime);
            int bound=name.equals("pollIo")?5:name.equals("heartbeats")?4:1;
            assertThat(executor.getActiveCount()).isLessThanOrEqualTo(bound);
            assertThat(executor.getQueue().size()).isLessThanOrEqualTo(name.equals("controls")?8:name.equals("polls")?5:0);
        }
    }
    String executorSnapshot(JobDispatcher runtime) throws Exception {
        var values=new ArrayList<String>();
        for(String name:List.of("pollIo","heartbeats","polls","controls")) {
            var field=JobDispatcher.class.getDeclaredField(name);field.setAccessible(true);
            var executor=(ThreadPoolExecutor)field.get(runtime);
            values.add(name+"[active="+executor.getActiveCount()+",pending="+executor.getQueue().size()+",max="+executor.getMaximumPoolSize()+"]");
        }
        return values.toString();
    }
    @Test void everyCrashBoundaryReconstructsDurableComponentsAndRedisFailureCannotUndoSuccess() throws Exception {
        var f=fixture(50); user("r4-admin@example.test","ADMIN");
        List<Graph> generations=new ArrayList<>();
        try(var before=reconstruct("claim",cache)) {
            generations.add(before); assertThat(before.runtime().dispatch(QueueName.AI)).isTrue();
            assertThat(before.crashed().await(5,TimeUnit.SECONDS)).isTrue();
        }
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where type='COURSE_ENRICHMENT'");
        JobLease stale;
        try(var afterClaim=reconstruct("response",cache)) {
            generations.add(afterClaim); assertThat(afterClaim.store().recoverExpired(10)).isOne();
            assertThat(afterClaim.runtime().dispatch(QueueName.AI)).isTrue();
            assertThat(afterClaim.crashed().await(5,TimeUnit.SECONDS)).isTrue();
            stale=afterClaim.observedLease().get();
            assertThat(count("select processed_questions from course_submission_attempts")).isZero();
        }
        jdbc.update("update background_jobs set lease_until=clock_timestamp()-interval '1 second' where type='COURSE_ENRICHMENT'");
        try(var afterResponse=reconstruct("stage",cache)) {
            generations.add(afterResponse); assertThat(afterResponse.store().recoverExpired(10)).isOne();
            assertThat(afterResponse.runtime().dispatch(QueueName.AI)).isTrue();
            assertThat(afterResponse.crashed().await(5,TimeUnit.SECONDS)).isTrue();
            var lease=afterResponse.observedLease().get();
            assertThat(lease.token()).isNotEqualTo(stale.token());
            assertThat(afterResponse.store().complete(stale,JobState.SUCCEEDED,null)).isFalse();
            assertThat(count("select processed_questions from course_submission_attempts")).isEqualTo(25);
        }
        var client=RedisClient.create("redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
        var failures=new AtomicInteger();
        try(var owner=client.connect()) {
            owner.sync().set("r4:stale","cached-draft");
            assertThat(redis.execInContainer("redis-cli","ACL","SETUSER","r4reader","on",">fixture-only","~r4:*","+get","+ping").getStdout()).contains("OK");
            var deniedClient=RedisClient.create("redis://r4reader:fixture-only@"+redis.getHost()+":"+redis.getMappedPort(6379));
            try(var denied=deniedClient.connect()) {
                var failingCache=new CacheInvalidationService(null,null,null) {
                    @Override public void evictTeacherDashboard(Long id) {
                        // Independent connection proves COMMIT has happened before invalidation.
                        try(var connection=dataSource.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("select state from course_submission_attempts")) {
                            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("SUCCEEDED");
                        } catch(java.sql.SQLException e) { throw new AssertionError(e); }
                        try { denied.sync().del("r4:stale"); } catch(io.lettuce.core.RedisCommandExecutionException e) { failures.incrementAndGet(); throw e; }
                    }
                    @Override public void evictGlobalCourseCatalog() { }
                    @Override public void evictAdminDashboard() { }
                };
                try(var afterStage=reconstruct(null,failingCache)) {
                    generations.add(afterStage);
                    assertThat(afterStage.runtime().dispatch(QueueName.AI)).isTrue();
                    await(()->count("select processed_questions from course_submission_attempts")==50);
                    await(()->afterStage.runtime().dispatch(QueueName.AI));
                    await(()->failures.get()==1);
                }
                assertThat(owner.sync().get("r4:stale")).isEqualTo("cached-draft");
            } finally { deniedClient.shutdown(); }
        } finally { client.shutdown(); }
        try(var afterFinalization=reconstruct(null,cache)) {
            generations.add(afterFinalization);
            assertThat(afterFinalization.repository().parent(f.submission()).state()).isEqualTo("SUCCEEDED");
            assertThat(afterFinalization.runtime().dispatch(QueueName.AI)).isFalse();
            await(()->{afterFinalization.runtime().dispatch(QueueName.MAIL_NOTICE);return fake.accepted.size()==1;});
        }
        assertThat(fake.calls).hasValue(3); assertThat(failures).hasValue(1);
        assertThat(count("select count(distinct question_revision_id) from course_submission_questions")).isEqualTo(50);
        assertThat(count("select count(*) from question_revisions where knowledge_point='present_simple'")).isEqualTo(50);
        assertThat(count("select count(*) from user_notifications")).isOne();
        for(int i=1;i<generations.size();i++) {
            var a=generations.get(i-1); var b=generations.get(i);
            assertThat(b.store()).isNotSameAs(a.store()); assertThat(b.repository()).isNotSameAs(a.repository());
            assertThat(b.checkpoints()).isNotSameAs(a.checkpoints()); assertThat(b.finalizer()).isNotSameAs(a.finalizer());
            assertThat(b.handler()).isNotSameAs(a.handler()); assertThat(b.runtime()).isNotSameAs(a.runtime());
        }
        System.out.printf("R4_RECONSTRUCTION generations=%d redisDeniedInvalidations=%d providerCalls=%d%n",generations.size(),failures.get(),fake.calls.get());
    }
    @Test void crashBoundariesKeepStagesUniqueFenceOldTokensAndAllowSmtpDuplicate() {
        var f=fixture(50);
        var old=claim(); expire(old); // Durable claim, no handler entry.
        var current=claim(); assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(store.complete(old,JobState.SUCCEEDED,null)).isFalse();
        var turn=checkpoints.load(f.submission());
        var enrichment=new CourseReviewEnrichmentService(fake,difficulty,xp);
        var response=enrichment.enrichBatch(turn.context(),turn.parent().contract()); // Model response, no checkpoint.
        expire(current);
        assertThatThrownBy(()->checkpoints.checkpoint(current,turn,response)).isInstanceOf(IllegalStateException.class);
        var worker=new CourseSubmissionHandler(checkpoints,new CourseReviewEnrichmentService(fake,difficulty,xp),completion,mapper);
        worker.execute(claim()); // Committed stage/yield; discard worker before dispatcher return.
        assertThat(count("select processed_questions from course_submission_attempts")).isEqualTo(25);
        worker=new CourseSubmissionHandler(checkpoints,new CourseReviewEnrichmentService(fake,difficulty,xp),completion,mapper);
        worker.execute(claim()); worker.execute(claim());
        assertThat(fake.calls).hasValue(3); // Remote replay is possible; committed batches are not replayed.
        assertThat(count("select count(*) from course_submission_questions")).isEqualTo(50);
        assertThat(count("select count(distinct question_revision_id) from course_submission_questions")).isEqualTo(50);
        assertThat(count("select count(*) from user_notifications")).isOne();
        try(var reconstructed=runtime()) { assertThat(reconstructed.dispatch(QueueName.AI)).isFalse(); }
        UUID payment=payment(); fake.loseAck=true;
        try(var graph=reconstruct(null,cache)) {
            assertThat(graph.runtime().dispatch(QueueName.MAIL_NOTICE)).isTrue();
            await(()->"RETRY_WAIT".equals(state(payment)));
        }
        assertThat(fake.accepted).hasSize(1);
        jdbc.update("update background_jobs set available_at=clock_timestamp()-interval '1 second' where id=?",payment);
        try(var graph=reconstruct(null,cache)) {
            assertThat(graph.runtime().dispatch(QueueName.MAIL_NOTICE)).isTrue(); await(()->"SUCCEEDED".equals(state(payment)));
        }
        assertThat(fake.accepted).hasSize(2);
        assertThat(fake.accepted.get(0)).isEqualTo(fake.accepted.get(1));
        assertThat(count("select count(*) from orders where status='PAID'")).isOne();
        assertThat(count("select count(*) from background_jobs where type='PAYMENT_CONFIRMATION_MAIL'")).isOne();
    }
}

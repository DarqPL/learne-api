package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.domain.auth.*;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.dto.*;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient;
import com.learne.backend.teacher.content.enums.*;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.repository.*;
import com.learne.backend.teacher.content.service.*;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.sql.Connection;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** No ambient test transaction: every service invocation completes through its real proxy. */
@ActiveProfiles("test")
@SpringBootTest(properties = "app.cloudinary.cloud-name=test-cloud")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@org.springframework.context.annotation.Import({com.learne.backend.testsupport.SubmissionTestDriver.class, com.learne.backend.testsupport.MediaTestDriver.class})
abstract class AudioPostgresTestSupport {
    private static GenericContainer<?> postgres;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        postgres = new GenericContainer<>("postgres:17-alpine")
                .withEnv("POSTGRES_DB", "audio_it")
                .withEnv("POSTGRES_USER", "audio_test")
                .withEnv("POSTGRES_PASSWORD", "isolated-audio-test-password")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
        postgres.start(); // Docker failure is a failure, never a skipped test or H2 fallback.
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/audio_it";
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> "audio_test");
        properties.add("spring.datasource.password", () -> "isolated-audio-test-password");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.flyway.locations", () -> "classpath:db/migration");
        properties.add("app.cache.redis.enabled", () -> false);
        properties.add("app.rate-limit.enabled", () -> false);
        properties.add("app.jobs.worker-enabled", () -> false);
    }

    @AfterAll
    static void stopPostgres() { if (postgres != null) postgres.stop(); }

    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TeacherContentService content;
    @Autowired com.learne.backend.testsupport.SubmissionTestDriver submissions;
    @Autowired CourseRevisionRepository revisions;
    @Autowired LessonRepository lessons;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired LessonAudioService audio;
    @Autowired LessonAudioReadService reads;
    @Autowired LessonAudioCleanupService cleanup;
    @Autowired com.learne.backend.testsupport.MediaTestDriver media;
    @MockitoBean StorageService storage;
    @MockitoBean ClamAvClient scanner;
    @MockitoBean CourseKnowledgePointClient knowledge;
    LessonAudioTestFixture.Draft draft;
    ExecutorService workers;

    @BeforeEach
    void fixture() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            System.out.println("AUDIO_IT_DATABASE=" + connection.getMetaData().getDatabaseProductName()
                    + " " + connection.getMetaData().getDatabaseProductVersion());
        }
        // V12 uses CASCADE for revision ownership and SET NULL for live source FKs;
        // cleaner deletion order is valid on PostgreSQL as well as H2.
        TestDatabaseCleaner.clean(jdbc);
        draft = new LessonAudioTestFixture(users, passwords, content, revisions, manager, mvc).draft();
        content.createQuestion(draft.teacher(), draft.liveLessonId(), new QuestionRequest(
                QuestionType.SHORT_ANSWER, "When?", "Explanation", "Early", "daily-routine",
                1, "EASY", 5, java.util.List.of()));
        // JSON synchronization rebuilds revision children; discover their current IDs.
        long lessonId = jdbc.queryForObject("select id from lesson_revisions where source_lesson_id = ?",
                Long.class, draft.liveLessonId());
        draft = new LessonAudioTestFixture.Draft(draft.teacher(), draft.accessToken(), draft.courseId(),
                draft.liveLessonId(), draft.revisionId(), lessonId);
        workers = Executors.newFixedThreadPool(3);
        doAnswer(i -> { outsideTransaction(); return null; }).when(scanner).assertClean(anyString(), any());
        doAnswer(i -> { outsideTransaction(); return null; }).when(storage).delete(any(), any());
        when(storage.uploadLessonAudio(any())).thenAnswer(i -> {
            outsideTransaction(); return uploaded("new");
        });
    }

    @AfterEach
    void stopWorkers() throws Exception {
        if (workers == null) return;
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    static LessonAudioState state(String name) {
        return new LessonAudioState("https://res.cloudinary.com/test-cloud/video/upload/v1/learne/course-audio/"
                + name + ".mp3", "learne/course-audio/" + name);
    }
    static StorageUploadResult uploaded(String name) { var s = state(name); return new StorageUploadResult(s.url(), s.publicId()); }
    LessonResponse upload() { return audio.upload(draft.teacher(), AudioTargetKind.REVISION, draft.revisionLessonId(), file("new")); }
    static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name + ".mp3", "audio/mpeg", new byte[]{'I', 'D', '3', 0, 0, (byte) name.charAt(0)});
    }
    void seedAudio(String name) {
        var value = state(name);
        jdbc.update("update lessons set audio_url=?, audio_public_id=? where id=?", value.url(), value.publicId(), draft.liveLessonId());
        jdbc.update("update lesson_revisions set audio_url=?, audio_public_id=? where id=?", value.url(), value.publicId(), draft.revisionLessonId());
    }
    void assertPair(String table, long id, LessonAudioState expected) {
        var row = jdbc.queryForMap("select audio_url, audio_public_id from " + table + " where id=?", id);
        assertThat(row.get("audio_url")).isEqualTo(expected.url());
        assertThat(row.get("audio_public_id")).isEqualTo(expected.publicId());
    }
    void assertMirrored(LessonAudioState expected) {
        assertPair("lessons", draft.liveLessonId(), expected);
        long current = jdbc.queryForObject("select id from lesson_revisions where source_lesson_id=? order by id desc limit 1",
                Long.class, draft.liveLessonId());
        assertPair("lesson_revisions", current, expected);
    }
    static void outsideTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("orchestration latch").isTrue();
    }
    static <T> T get(Future<T> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }
    static void conflict(Future<?> future) {
        assertThatThrownBy(() -> future.get(15, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                e -> assertThat(e.getCause()).isInstanceOfSatisfying(ApiException.class,
                        api -> assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT)));
    }
    static void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                api -> assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** Storage return is outside transactions, after preflight; release chooses finalize order. */
    Gate gateUpload() {
        Gate gate = new Gate();
        when(storage.uploadLessonAudio(any())).thenAnswer(i -> {
            outsideTransaction(); gate.entered.countDown(); await(gate.release); return uploaded("new");
        });
        return gate;
    }
    static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        void entered() throws InterruptedException { await(entered); }
        @Override public void close() { release.countDown(); }
    }

    /** Proves a real database wait on this course, then checks revision is still lockable NOWAIT. */
    <T> T courseBlocks(Callable<T> writer) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int pid;
                try (var statement = holder.createStatement(); var result = statement.executeQuery("select pg_backend_pid()")) {
                    result.next(); pid = result.getInt(1);
                }
                try (var statement = holder.prepareStatement("select id from courses where id=? for update")) {
                    statement.setLong(1, draft.courseId()); statement.executeQuery().close();
                }
                Future<T> future = workers.submit(writer);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < deadline && !future.isDone()) {
                    blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                            "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))", Boolean.class, pid));
                    if (blocked) break;
                    Thread.onSpinWait();
                }
                assertThat(blocked).as("writer waits in PostgreSQL on held course row").isTrue();
                assertThat(future.isDone()).isFalse();
                try (var statement = holder.prepareStatement("select id from course_revisions where id=? for update nowait")) {
                    statement.setLong(1, draft.revisionId()); statement.executeQuery().close();
                }
                holder.commit();
                return get(future);
            } finally { holder.rollback(); }
        }
    }

    /** Queue two independent service transactions behind a real row lock, in chosen order. */
    void courseRace(Callable<?> first, Callable<?> second) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var statement = holder.prepareStatement("select id from courses where id=? for update")) {
                    statement.setLong(1, draft.courseId()); statement.executeQuery().close();
                }
                var a = workers.submit(first);
                awaitBlockedWriters(1);
                var b = workers.submit(second);
                awaitBlockedWriters(2);
                assertThat(a.isDone()).isFalse(); assertThat(b.isDone()).isFalse();
                holder.commit();
                get(a); get(b);
            } finally { holder.rollback(); }
        }
    }

    private void awaitBlockedWriters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int blocked = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and cardinality(pg_blocking_pids(pid))>0", Integer.class);
            if (blocked >= expected) return;
            Thread.onSpinWait();
        }
        fail("Expected " + expected + " native PostgreSQL lock waiters");
    }
}

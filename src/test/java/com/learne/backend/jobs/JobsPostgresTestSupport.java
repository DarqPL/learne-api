package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.testsupport.TestDatabaseCleaner;
import com.learne.backend.jobs.service.JobEnqueuer;
import com.learne.backend.jobs.service.JobStore;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Native PostgreSQL fixture; service calls have no ambient test transaction. */
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
public abstract class JobsPostgresTestSupport {
    protected static GenericContainer<?> postgres;
    @Autowired protected PlatformTransactionManager manager;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected JobStore store;
    @Autowired protected JobEnqueuer enqueuer;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        postgres = new GenericContainer<>("postgres:17-alpine")
                .withEnv("POSTGRES_DB", "jobs_it")
                .withEnv("POSTGRES_USER", "jobs_test")
                .withEnv("POSTGRES_PASSWORD", "isolated-jobs-test-password")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/jobs_it";
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> "jobs_test");
        properties.add("spring.datasource.password", () -> "isolated-jobs-test-password");
        properties.add("spring.datasource.hikari.connection-init-sql",
                // Exercise recoverable statement cancellation before the 5s socket/transaction deadline.
                () -> "SET statement_timeout='2s'; SET lock_timeout='5s'");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.flyway.locations", () -> "classpath:db/migration");
        properties.add("app.cache.redis.enabled", () -> false);
        properties.add("app.rate-limit.enabled", () -> false);
        properties.add("app.jobs.worker-enabled", () -> false);
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TestDatabaseCleaner.clean(jdbc);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }
}

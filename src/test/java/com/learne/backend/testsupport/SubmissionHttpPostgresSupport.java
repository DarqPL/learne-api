package com.learne.backend.testsupport;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Native database for HTTP fixtures that exercise the real submission pipeline. */
@org.springframework.context.annotation.Import(SubmissionTestDriver.class)
public abstract class SubmissionHttpPostgresSupport {
    private static final class Database {
        static final GenericContainer<?> PG=new GenericContainer<>("postgres:17-alpine")
            .withEnv("POSTGRES_DB","http_it").withEnv("POSTGRES_USER","http_test")
            .withEnv("POSTGRES_PASSWORD","isolated-http-password").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2));
        static { PG.start(); }
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var pg=Database.PG;
        properties.add("spring.datasource.url",()->"jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/http_it");
        properties.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        properties.add("spring.datasource.username",()->"http_test");
        properties.add("spring.datasource.password",()->"isolated-http-password");
        properties.add("spring.flyway.locations",()->"classpath:db/migration");
        properties.add("app.jobs.worker-enabled",()->false);
    }
    @Autowired private SubmissionTestDriver submissionDriver;
    protected void submitAndProcess(MockMvc mvc,String teacherToken,long courseId) throws Exception {
        var result=mvc.perform(post("/api/teacher/content/courses/{id}/submit",courseId)
                .header("Authorization","Bearer "+teacherToken))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andReturn();
        var body=new tools.jackson.databind.json.JsonMapper().readTree(result.getResponse().getContentAsString());
        submissionDriver.finish(UUID.fromString(body.get("id").asText()));
    }
}

package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

class JobRetryCommandTests {
    @Test void actualMaintenanceContextIsNonwebAndContainsOnlyExplicitInfrastructure() throws Exception {
        Class<?> source = Class.forName("com.learne.backend.jobs.maintenance.JobMaintenanceApplication");
        var application = new SpringApplication(source);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run("--spring.config.location=optional:classpath:/maintenance-test.properties",
                "--spring.datasource.url=jdbc:h2:mem:maintenance", "--spring.datasource.username=sa",
                "--spring.datasource.password=", "--spring.main.banner-mode=off")) {
            assertThat(context.getEnvironment().getProperty("local.server.port")).isNull();
            for (String name : context.getBeanDefinitionNames()) {
                Object bean = context.getBean(name);
                String type = bean.getClass().getName();
                assertThat(type).doesNotContain("SecurityConfig", "JobConfiguration", "BackendApplication",
                        "JobDispatcher", "JobMaintenanceService", "CloudinaryMediaDeleteAdapter",
                        "MailTransport", "JavaMailSender", "AiServiceClient");
                assertThat(bean).isNotInstanceOf(com.learne.backend.jobs.service.JobHandler.class);
            }
            assertThat(SpringApplication.exit(context)).isEqualTo(2);
        }
    }
}

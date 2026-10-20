package com.learne.backend.jobs.maintenance;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.learne.backend.mail.service.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit bean source, deliberately neither a configuration component nor a component scan. */
public class JobMaintenanceApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(JobMaintenanceApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(java.util.Map.of("spring.config.name", "job-maintenance"));
        try {
            int exit;
            try (var context = application.run(args)) { exit = SpringApplication.exit(context); }
            System.exit(exit);
        }
        catch (RuntimeException failure) { System.err.println("JOB_MAINTENANCE_START_FAILED"); System.exit(2); }
    }
    @Bean DataSource maintenanceDataSource(Environment env) {
        var ds = new DriverManagerDataSource();
        ds.setUrl(env.getRequiredProperty("spring.datasource.url"));
        ds.setUsername(env.getRequiredProperty("spring.datasource.username"));
        ds.setPassword(env.getRequiredProperty("spring.datasource.password"));
        return ds;
    }
    @Bean JdbcTemplate maintenanceJdbc(DataSource ds) {
        var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(5); return jdbc;
    }
    @Bean DataSourceTransactionManager maintenanceTransactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean JobRetryCommand retryCommand(JdbcTemplate jdbc, DataSourceTransactionManager tx, Environment env) {
        var mapper = JsonMapper.builder().build();
        return new JobRetryCommand(jdbc, tx, new MailPayloadCodec(mapper),
                new MailEligibilityService(jdbc,tx,new BCryptPasswordEncoder(),Clock.systemUTC()), mapper,
                env.getProperty("app.cloudinary.cloud-name", ""));
    }
}

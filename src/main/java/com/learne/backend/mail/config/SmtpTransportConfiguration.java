package com.learne.backend.mail.config;

import com.learne.backend.mail.service.*;
import java.time.Duration;
import java.util.Properties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Uses the same spring.mail source as the existing business mail services. */
@AutoConfiguration(before = MailJobConfiguration.class)
public class SmtpTransportConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(MailTransport.class)
    SmtpMailTransport smtpMailTransport(Environment env) {
        var p = new Properties();
        p.setProperty("mail.smtp.host", env.getProperty("spring.mail.host", "localhost"));
        p.setProperty("mail.smtp.port", env.getProperty("spring.mail.port", "25"));
        p.setProperty("mail.transport.protocol", env.getProperty("spring.mail.protocol", "smtp"));
        for (String key : new String[]{"auth", "starttls.enable", "starttls.required", "ssl.enable", "connectiontimeout", "timeout", "writetimeout"}) {
            String value = env.getProperty("spring.mail.properties.mail.smtp." + key);
            if (value != null) p.setProperty("mail.smtp." + key, value);
        }
        return new SmtpMailTransport(p, env.getProperty("spring.mail.username", ""), env.getProperty("spring.mail.password", ""),
                Duration.ofMillis(env.getProperty("app.mail.delivery-deadline-ms", Long.class, 45000L)));
    }
}

package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.mail.service.*;
import com.learne.backend.mail.config.SmtpTransportConfiguration;
import com.learne.backend.jobs.service.*;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.SimpleMailMessage;

class SmtpMailTransportTests {
    static Properties settings(int port) {
        var p = new Properties();
        p.setProperty("mail.smtp.host", "127.0.0.1");
        p.setProperty("mail.smtp.port", "" + port);
        return p;
    }
    static SimpleMailMessage message() {
        var m = new SimpleMailMessage(); m.setFrom("sender@example.test");
        m.setTo("recipient@example.test"); m.setSubject("Xin chào Việt Nam"); m.setText("Nội dung tiếng Việt");
        return m;
    }
    @Test void defaultsAreBoundedAndTlsVerificationIsEnabled() {
        try (var transport = new SmtpMailTransport(settings(25), "", "", Duration.ofSeconds(45))) {
            var p = transport.sessionProperties();
            assertThat(p.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
            assertThat(p.getProperty("mail.smtp.timeout")).isEqualTo("15000");
            assertThat(p.getProperty("mail.smtp.writetimeout")).isEqualTo("15000");
            assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
            assertThat(p).doesNotContainKey("mail.smtp.ssl.trust");
            assertThat(p.getProperty("mail.debug")).isEqualTo("false");
        }
    }
    @Test void invalidAddressAndUnsupportedConfigurationArePermanentAndSanitized() {
        try (var transport = new SmtpMailTransport(settings(25), "", "", Duration.ofMillis(300))) {
            var m = message(); m.setTo("bad\r\nsecret@example.test");
            assertThatThrownBy(() -> transport.send(m)).isInstanceOf(PermanentJobException.class)
                    .hasMessage("SMTP_INVALID_ADDRESS").hasNoCause();
        }
        var p = settings(25); p.setProperty("mail.transport.protocol", "smtps");
        assertThatThrownBy(() -> new SmtpMailTransport(p, "secret-user", "secret-pass", Duration.ofMillis(300)))
                .isInstanceOf(PermanentJobException.class).hasMessage("SMTP_CONFIGURATION").hasNoCause();
    }
    @Test void autoConfigurationCreatesWithoutConnectingAndNativeFakeWins() {
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SmtpTransportConfiguration.class))
                .withPropertyValues("spring.mail.host=127.0.0.1", "spring.mail.port=1",
                        "spring.mail.properties.mail.smtp.auth=true", "spring.mail.properties.mail.smtp.starttls.enable=true",
                        "spring.mail.username=test", "spring.mail.password=test");
        runner.run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(MailTransport.class);
            var p = c.getBean(SmtpMailTransport.class).sessionProperties();
            assertThat(p.getProperty("mail.smtp.auth")).isEqualTo("true");
            assertThat(p.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        });
        MailTransport fake = m -> { throw new AssertionError("fake must not send at startup"); };
        runner.withBean(MailTransport.class, () -> fake).run(c -> assertThat(c.getBean(MailTransport.class)).isSameAs(fake));
    }
    @Test void realTransportInstallsAllHandlersEvenWithJavaMailSenderInterfaceMock() {
        new MailJobConfigurationTests().runner(true)
                .withConfiguration(AutoConfigurations.of(SmtpTransportConfiguration.class))
                .withBean(org.springframework.mail.javamail.JavaMailSender.class,
                        () -> org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class))
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(SmtpMailTransport.class).hasSingleBean(MailJobProducer.class);
                    assertThat(c.getBeansOfType(JobHandler.class)).hasSize(6);
                });
    }
}

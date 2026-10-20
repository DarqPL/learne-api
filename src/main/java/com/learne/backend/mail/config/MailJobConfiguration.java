package com.learne.backend.mail.config;

import com.learne.backend.jobs.crypto.JobPayloadCipher;
import com.learne.backend.jobs.model.JobType;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.service.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Evaluated after application bean definitions, so a transport explicitly activates this phase. */
@AutoConfiguration
@ConditionalOnBean(MailTransport.class)
public class MailJobConfiguration {
    @Bean MailJobProducer mailJobProducer(JobEnqueuer enqueuer, JobPayloadCipher cipher, ObjectMapper mapper) {
        return new MailJobProducer(enqueuer, cipher, mapper);
    }
    @Bean MailPayloadCodec mailPayloadCodec(ObjectMapper mapper) { return new MailPayloadCodec(mapper); }
    @Bean MailEligibilityService mailEligibilityService(JdbcTemplate jdbc, PlatformTransactionManager manager, PasswordEncoder passwords) {
        return new MailEligibilityService(jdbc, manager, passwords, Clock.systemUTC());
    }
    @Bean MailTemplateRenderer mailTemplateRenderer(@Value("${app.mail.frontend-base-url:http://localhost:5173}") String base,
                                                   @Value("${app.mail.from:}") String from) {
        return new MailTemplateRenderer(base, from);
    }
    @Bean JobHandler registrationOtpMailHandler(@Lazy JobStore store, JobPayloadCipher cipher, MailPayloadCodec codec, MailEligibilityService eligibility, MailTemplateRenderer renderer, MailTransport transport) {
        return new MailJobHandler(JobType.REGISTRATION_OTP_MAIL, store, cipher, codec, eligibility, renderer, transport);
    }
    @Bean JobHandler passwordResetMailHandler(@Lazy JobStore store, JobPayloadCipher cipher, MailPayloadCodec codec, MailEligibilityService eligibility, MailTemplateRenderer renderer, MailTransport transport) {
        return new MailJobHandler(JobType.PASSWORD_RESET_MAIL, store, cipher, codec, eligibility, renderer, transport);
    }
    @Bean JobHandler teacherInvitationMailHandler(@Lazy JobStore store, JobPayloadCipher cipher, MailPayloadCodec codec, MailEligibilityService eligibility, MailTemplateRenderer renderer, MailTransport transport) {
        return new MailJobHandler(JobType.TEACHER_INVITATION_MAIL, store, cipher, codec, eligibility, renderer, transport);
    }
    @Bean JobHandler paymentConfirmationMailHandler(@Lazy JobStore store, JobPayloadCipher cipher, MailPayloadCodec codec, MailEligibilityService eligibility, MailTemplateRenderer renderer, MailTransport transport) {
        return new MailJobHandler(JobType.PAYMENT_CONFIRMATION_MAIL, store, cipher, codec, eligibility, renderer, transport);
    }
    @Bean JobHandler coursePendingReviewMailHandler(@Lazy JobStore store, JobPayloadCipher cipher, MailPayloadCodec codec, MailEligibilityService eligibility, MailTemplateRenderer renderer, MailTransport transport) {
        return new MailJobHandler(JobType.COURSE_PENDING_REVIEW_MAIL, store, cipher, codec, eligibility, renderer, transport);
    }
    @Bean JobHandler courseReviewResultMailHandler(@Lazy JobStore store, JobPayloadCipher cipher, MailPayloadCodec codec, MailEligibilityService eligibility, MailTemplateRenderer renderer, MailTransport transport) {
        return new MailJobHandler(JobType.COURSE_REVIEW_RESULT_MAIL, store, cipher, codec, eligibility, renderer, transport);
    }
}

package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.learne.backend.jobs.service.RetryableJobException;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.MailEligibilityService;
import com.learne.backend.mail.service.MailEligibilityService.Result;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MailEligibilityTests {
    static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    static final String GENERATION = "11111111-1111-1111-1111-111111111111";
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    MailEligibilityService service() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new MailEligibilityService(jdbc, manager, new BCryptPasswordEncoder(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static MailPayload auth(MailTemplate template, long seconds) {
        return new MailPayload(template == MailTemplate.REGISTRATION_OTP ? 0 : 7, "student@example.test", "Student",
                template, template == MailTemplate.PASSWORD_RESET ? "42" : GENERATION,
                NOW.plusSeconds(seconds), null, null, null, null, null, Map.of());
    }

    @Test void twentyNineSecondsSkipsBeforeAnyDatabaseRead() {
        assertThat(service().check(auth(MailTemplate.REGISTRATION_OTP, 29))).isEqualTo(Result.CODE_EXPIRED);
        verifyNoInteractions(jdbc);
    }

    @Test void thirtySecondsIsEligibleAndAttemptFourAllowedButFiveSkipped() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "mail_generation", UUID.fromString(GENERATION), "expires_at", java.sql.Timestamp.from(NOW.plusSeconds(30)), "attempts", 4)));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        var service = service();
        assertThat(service.check(auth(MailTemplate.REGISTRATION_OTP, 30))).isEqualTo(Result.ALLOW);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "mail_generation", UUID.fromString(GENERATION), "expires_at", java.sql.Timestamp.from(NOW.plusSeconds(30)), "attempts", 5)));
        assertThat(service.check(auth(MailTemplate.REGISTRATION_OTP, 30))).isEqualTo(Result.VERIFICATION_ATTEMPTS_EXHAUSTED);
    }

    @Test void databaseFailureIsSanitizedRetryableNotMissingRecipient() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("private SQL and email and hash"));
        assertThatThrownBy(() -> service().check(auth(MailTemplate.PASSWORD_RESET, 300)))
                .isInstanceOf(RetryableJobException.class).hasMessage("MAIL_ELIGIBILITY_UNAVAILABLE").hasNoCause();
    }
}

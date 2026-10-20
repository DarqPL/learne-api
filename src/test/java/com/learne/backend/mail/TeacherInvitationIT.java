package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.admin.user.dto.CreateTeacherRequest;
import com.learne.backend.admin.user.service.AdminUserService;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(MailDeliveryIT.TransportFixture.class)
@TestPropertySource(properties = {
    "management.health.mail.enabled=false",
    "app.job-crypto.active-key-id=mail-test",
    "app.job-crypto.keys-json={\"mail-test\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\"}",
    "app.mail.frontend-base-url=https://learne.test"})
class TeacherInvitationIT extends MailPostgresTestSupport {
    @Autowired AdminUserService admin;
    @Autowired com.learne.backend.admin.user.service.TeacherInvitationService invitations;
    @Autowired MailPayloadCodec codec;
    @Autowired com.learne.backend.jobs.crypto.JobPayloadCipher cipher;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired JobRegistry registry;
    @Autowired MailDeliveryIT.FakeTransport transport;
    @MockitoSpyBean JobStore jobStore;
    @MockitoSpyBean org.springframework.jdbc.core.JdbcTemplate observedJdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean org.springframework.mail.javamail.JavaMailSender smtp;

    @org.junit.jupiter.api.AfterEach void neverUsesSynchronousSmtp() { verifyNoInteractions(smtp); }

    @Test void createsAccountAndEncryptedInvitationAtomicallyWithoutTransport() {
        Instant before = Instant.now();
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        assertThat(user.getInvitationGeneration()).isNotNull();
        var lease = store.claim(QueueName.MAIL_AUTH, Map.of(JobType.TEACHER_INVITATION_MAIL, Set.of(1)), Duration.ofSeconds(30)).orElseThrow();
        var payload = codec.decode(lease.type(), lease.payloadJson());
        assertThat(payload.generation()).isEqualTo(user.getInvitationGeneration().toString());
        assertThat(payload.expiresAt()).isBetween(before.plusSeconds(86400), Instant.now().plusSeconds(86400));
        var secret = cipher.decrypt(lease.jobId(), lease.type(), payload.generation(), lease.secret());
        try { assertThat(passwords.matches(new String(secret, java.nio.charset.StandardCharsets.UTF_8), user.getPasswordHash())).isTrue(); }
        finally { Arrays.fill(secret, (byte) 0); }
        assertThat(transport.delivered).isEmpty();
        transport.nextFailure = new RetryableJobException("THROTTLED", null);
        assertThatThrownBy(() -> registry.find(lease.type(), 1).orElseThrow().execute(lease)).isInstanceOf(RetryableJobException.class);
        assertThat(jdbc.queryForObject("select count(*) from users where id=?", Long.class, user.getId())).isEqualTo(1);
    }

    @Test void enqueueFailureRollsBackInitialAccount() {
        JobStore target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobStore);
        doThrow(new JobEnqueueException(null)).when(target).enqueue(any());
        assertThatThrownBy(() -> admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"))).isInstanceOf(JobEnqueueException.class);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test void pendingRejectsThenExpiredResendRotatesAndOldWorkerSkips() {
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        var original = jdbc.queryForMap("select password_hash,invitation_generation from users where id=?", user.getId());
        jdbc.update("insert into refresh_tokens(user_id,token_hash,expires_at) values(?,'committed-session',clock_timestamp()+interval '1 day')", user.getId());
        assertThatThrownBy(() -> invitations.resend(user.getId())).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        assertThat(invitations.deliveryByTeacherIds(List.of(user.getId())).get(user.getId()).canResend()).isFalse();
        var old = store.claim(QueueName.MAIL_AUTH, Map.of(JobType.TEACHER_INVITATION_MAIL, Set.of(1)), Duration.ofSeconds(30)).orElseThrow();
        var oldPayload = codec.decode(old.type(), old.payloadJson());
        var oldSecret = cipher.decrypt(old.jobId(), old.type(), oldPayload.generation(), old.secret());
        assertThatThrownBy(() -> invitations.resend(user.getId())).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
        jdbc.update("update background_jobs set expires_at=clock_timestamp()-interval '1 second'");
        assertThat(invitations.deliveryByTeacherIds(List.of(user.getId())).get(user.getId()).canResend()).isTrue();
        assertThat(invitations.resend(user.getId()).status()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select revoked from refresh_tokens", Boolean.class)).isTrue();
        assertThat(jdbc.queryForMap("select password_hash,invitation_generation from users where id=?", user.getId()))
                .allSatisfy((key, value) -> assertThat(value).isNotEqualTo(original.get(key)));
        try { assertThat(passwords.matches(new String(oldSecret, java.nio.charset.StandardCharsets.UTF_8), jdbc.queryForObject("select password_hash from users where id=?", String.class, user.getId()))).isFalse(); }
        finally { Arrays.fill(oldSecret, (byte) 0); }
        registry.find(old.type(), 1).orElseThrow().execute(old);
        assertThat(jdbc.queryForObject("select state from background_jobs where id=?", String.class, old.jobId())).isEqualTo("SKIPPED");
    }

    @Test void missingHistoryAndLegacySummariesUseCurrentGenerationAndEligibility() {
        var a = admin.createTeacher(new CreateTeacherRequest("a@example.test", "A"));
        var b = admin.createTeacher(new CreateTeacherRequest("b@example.test", "B"));
        jdbc.update("update users set invitation_generation=? where id=?", UUID.randomUUID(), a.getId());
        jdbc.update("update users set invitation_generation=null,is_locked=true where id=?", b.getId());
        var statuses = invitations.deliveryByTeacherIds(List.of(a.getId(), b.getId()));
        assertThat(statuses.get(a.getId()).status()).isEqualTo("HISTORY_UNAVAILABLE");
        assertThat(statuses.get(a.getId()).canResend()).isTrue();
        assertThat(statuses.get(b.getId()).status()).isEqualTo("NOT_REQUESTED");
        assertThat(statuses.get(b.getId()).canResend()).isFalse();
        assertThat(invitations.resend(a.getId()).status()).isEqualTo("QUEUED");
        assertThatThrownBy(() -> invitations.resend(b.getId())).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
    }

    @Test void failedResendRestoresHashAndGeneration() {
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        jdbc.update("delete from background_jobs");
        var original = jdbc.queryForMap("select * from users where id=?", user.getId());
        jdbc.update("insert into refresh_tokens(user_id,token_hash,expires_at) values(?,'committed-session',clock_timestamp()+interval '1 day')", user.getId());
        JobStore target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobStore);
        doThrow(new JobEnqueueException(null)).when(target).enqueue(any());
        assertThatThrownBy(() -> invitations.resend(user.getId())).isInstanceOf(JobEnqueueException.class);
        assertThat(jdbc.queryForMap("select * from users where id=?", user.getId())).isEqualTo(original);
        assertThat(jdbc.queryForObject("select revoked from refresh_tokens", Boolean.class)).isFalse();
    }

    @Test void concurrentResendsSerializeWithExactlyOneAccepted() throws Exception {
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        jdbc.update("delete from background_jobs");
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> resend = () -> {
            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            try { invitations.resend(user.getId()); return true; }
            catch (com.learne.backend.shared.api.ApiException conflict) { assertThat(conflict.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT); return false; }
        };
        try {
            var a = executor.submit(resend); var b = executor.submit(resend); start.countDown();
            assertThat(List.of(a.get(5, java.util.concurrent.TimeUnit.SECONDS), b.get(5, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(1);
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"is_active=false", "is_locked=true", "must_change_password=false", "role='STUDENT'"})
    void rejectsIneligibleAccountsWithoutRotation(String change) {
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        jdbc.update("delete from background_jobs");
        jdbc.update("update users set " + change + " where id=?", user.getId());
        var original = jdbc.queryForMap("select * from users where id=?", user.getId());
        assertThatThrownBy(() -> invitations.resend(user.getId())).isInstanceOfSatisfying(
                com.learne.backend.shared.api.ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(change.startsWith("role=")
                        ? org.springframework.http.HttpStatus.NOT_FOUND : org.springframework.http.HttpStatus.CONFLICT));
        assertThat(jdbc.queryForMap("select * from users where id=?", user.getId())).isEqualTo(original);
        assertThatThrownBy(() -> invitations.resend(-1L)).isInstanceOfSatisfying(
                com.learne.backend.shared.api.ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    private void state(long userId, String state) {
        jdbc.update("""
                update background_jobs set state=?,
                  lease_token=case when ?='RUNNING' then cast(? as uuid) else null end,
                  lease_until=case when ?='RUNNING' then clock_timestamp()+interval '1 minute' else null end,
                  completed_at=case when ? in ('SUCCEEDED','DEAD','SKIPPED') then clock_timestamp() else null end
                where payload->>'recipientUserId'=? and type='TEACHER_INVITATION_MAIL'
                """, state, state, UUID.randomUUID(), state, state, Long.toString(userId));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "READY,QUEUED,false", "RUNNING,SENDING,false", "RETRY_WAIT,RETRYING,false",
            "SUCCEEDED,SENT,true", "DEAD,FAILED,true", "SKIPPED,SKIPPED,true"})
    void currentStateMapsSafelyAndControlsExplicitResend(String state, String status, boolean canResend) {
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        state(user.getId(), state);
        var original = jdbc.queryForMap("select * from users where id=?", user.getId());
        var delivery = invitations.deliveryByTeacherIds(List.of(user.getId())).get(user.getId());
        assertThat(delivery.status()).isEqualTo(status);
        assertThat(delivery.canResend()).isEqualTo(canResend);
        assertThat(delivery.requestedAt()).isNotNull();
        assertThat(delivery.completedAt() != null).isEqualTo(canResend);
        assertThat(jdbc.queryForMap("select * from users where id=?", user.getId())).isEqualTo(original);
        if (canResend) {
            assertThat(invitations.resend(user.getId()).status()).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("select invitation_generation from users where id=?", UUID.class, user.getId())).isNotEqualTo(user.getInvitationGeneration());
            assertThat(jdbc.queryForObject("select password_hash from users where id=?", String.class, user.getId())).isNotEqualTo(user.getPasswordHash());
            assertThat(jdbc.queryForObject("select count(*) from background_jobs", Long.class)).isEqualTo(2);
        } else {
            assertThatThrownBy(() -> invitations.resend(user.getId())).isInstanceOfSatisfying(
                    com.learne.backend.shared.api.ApiException.class,
                    error -> assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
            assertThat(jdbc.queryForMap("select * from users where id=?", user.getId())).isEqualTo(original);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"is_active=false", "is_locked=true", "must_change_password=false"})
    void terminalAndExpiredDeliveryStillRequireEligibleAccount(String change) {
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        jdbc.update("update users set " + change + " where id=?", user.getId());
        state(user.getId(), "SUCCEEDED");
        assertThat(invitations.deliveryByTeacherIds(List.of(user.getId())).get(user.getId()).canResend()).isFalse();
        state(user.getId(), "READY");
        jdbc.update("update background_jobs set expires_at=clock_timestamp()-interval '1 second'");
        assertThat(invitations.deliveryByTeacherIds(List.of(user.getId())).get(user.getId()).canResend()).isFalse();
    }

    @Test void expiredReadyAndEligibleLegacyPermitFreshGenerationWithoutReadMutation() {
        var expired = admin.createTeacher(new CreateTeacherRequest("expired@example.test", "Expired"));
        var legacy = admin.createTeacher(new CreateTeacherRequest("legacy@example.test", "Legacy"));
        jdbc.update("delete from background_jobs where payload->>'recipientUserId'=?", legacy.getId().toString());
        jdbc.update("update users set invitation_generation=null where id=?", legacy.getId());
        jdbc.update("update background_jobs set expires_at=clock_timestamp()-interval '1 second'");
        var before = jdbc.queryForList("select * from users order by id");
        var deliveries = invitations.deliveryByTeacherIds(List.of(expired.getId(), legacy.getId()));
        assertThat(deliveries.get(expired.getId()).status()).isEqualTo("QUEUED");
        assertThat(deliveries.get(legacy.getId()).status()).isEqualTo("NOT_REQUESTED");
        assertThat(deliveries.values()).allSatisfy(d -> assertThat(d.canResend()).isTrue());
        assertThat(jdbc.queryForList("select * from users order by id")).isEqualTo(before);
        assertThat(invitations.resend(expired.getId()).status()).isEqualTo("QUEUED");
        assertThat(invitations.resend(legacy.getId()).status()).isEqualTo("QUEUED");
    }

    @Test void batchUsesOneNativeQueryAndNeverLeaksOtherUsersOrOldGenerations() {
        var a = admin.createTeacher(new CreateTeacherRequest("a@example.test", "A"));
        var b = admin.createTeacher(new CreateTeacherRequest("b@example.test", "B"));
        var c = admin.createTeacher(new CreateTeacherRequest("c@example.test", "C"));
        state(a.getId(), "DEAD");
        invitations.resend(a.getId()); // old FAILED plus current READY
        state(b.getId(), "SUCCEEDED");
        jdbc.update("update users set invitation_generation=? where id=?", a.getInvitationGeneration(), b.getId());
        // B must not pick up A's matching-generation FAILED job, or B's own previous SENT job.
        jdbc.update("delete from background_jobs where payload->>'recipientUserId'=?", c.getId().toString());
        clearInvocations(observedJdbc);
        var deliveries = invitations.deliveryByTeacherIds(List.of(a.getId(), b.getId(), c.getId()));
        var statement = org.mockito.ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        verify(observedJdbc, times(1)).query(statement.capture(), any(org.springframework.jdbc.core.RowCallbackHandler.class));
        // Count the execution-level overload as well, including any queries hidden behind other public overloads.
        verify(observedJdbc, times(1)).query(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                nullable(org.springframework.jdbc.core.PreparedStatementSetter.class), any(org.springframework.jdbc.core.ResultSetExtractor.class));
        assertThat(statement.getValue()).isInstanceOf(org.springframework.jdbc.core.SqlProvider.class);
        assertThat(((org.springframework.jdbc.core.SqlProvider) statement.getValue()).getSql())
                .contains("payload->>'recipientUserId'=u.id::text", "payload->>'generation'=u.invitation_generation::text", "TEACHER_INVITATION_MAIL");
        assertThat(deliveries).hasSize(3);
        assertThat(deliveries.get(a.getId()).status()).isEqualTo("QUEUED");
        assertThat(deliveries.get(b.getId()).status()).isEqualTo("HISTORY_UNAVAILABLE");
        assertThat(deliveries.get(c.getId()).status()).isEqualTo("HISTORY_UNAVAILABLE");
        clearInvocations(observedJdbc);
        assertThat(invitations.deliveryByTeacherIds(List.of())).isEmpty();
        verifyNoInteractions(observedJdbc);
    }

    @Test void failureCodesAreEnumOrNullAndSerializedDeliveryHasExactlyFiveSafeFields() throws Exception {
        var user = admin.createTeacher(new CreateTeacherRequest("teacher@example.test", "Teacher"));
        state(user.getId(), "DEAD");
        var mapper = new tools.jackson.databind.ObjectMapper();
        var codes = new ArrayList<String>(Arrays.stream(JobErrorCode.values()).map(Enum::name).toList());
        codes.addAll(List.of("UPPERCASE_SECRET_MARKER_8E21", "smtp_timeout", ""));
        for (String code : codes) {
            jdbc.update("update background_jobs set last_error_code=?", code);
            var delivery = invitations.deliveryByTeacherIds(List.of(user.getId())).get(user.getId());
            assertThat(delivery.failureCode()).isEqualTo(Arrays.stream(JobErrorCode.values()).anyMatch(approved -> approved.name().equals(code)) ? code : null);
            var tree = mapper.valueToTree(delivery);
            assertThat(tree.propertyNames()).containsExactlyInAnyOrder("status", "requestedAt", "completedAt", "failureCode", "canResend");
            assertThat(tree.toString()).doesNotContain("UPPERCASE_SECRET_MARKER_8E21", user.getInvitationGeneration().toString(), user.getPasswordHash());
        }
        jdbc.update("update background_jobs set last_error_code=null");
        assertThat(invitations.deliveryByTeacherIds(List.of(user.getId())).get(user.getId()).failureCode()).isNull();
    }
}

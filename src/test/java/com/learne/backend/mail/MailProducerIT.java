package com.learne.backend.mail;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.model.*;
import com.learne.backend.mail.model.*;
import com.learne.backend.mail.service.*;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class MailProducerIT extends MailPostgresTestSupport {
    @Autowired MailJobProducer producer;
    @Autowired ObjectMapper mapper;

    private MailPayload payload() { return MailTemplateRendererTests.payload(MailTemplate.REGISTRATION_OTP, Map.of()); }

    @Test void rejectsMissingTransactionBeforeQueueWrite() {
        assertThatThrownBy(() -> producer.enqueue(JobType.REGISTRATION_OTP_MAIL, "outside", payload(), new byte[]{65}))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Integer.class)).isZero();
    }

    @Test void rollbackRemovesEncryptedJob() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            producer.enqueue(JobType.REGISTRATION_OTP_MAIL, "rollback", payload(), new byte[]{65});
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from background_jobs", Integer.class)).isZero();
    }

    @Test void dedupePreservesFirstIdentityAndRealClaimCarriesOnlyEncryptedSecret() {
        var first = payload();
        UUID id = new TransactionTemplate(manager).execute(status -> producer.enqueue(
                JobType.REGISTRATION_OTP_MAIL, "duplicate", first, "private-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        UUID duplicate = new TransactionTemplate(manager).execute(status -> producer.enqueue(
                JobType.REGISTRATION_OTP_MAIL, "duplicate", payload(), new byte[]{66}));
        assertThat(duplicate).isEqualTo(id);
        var lease = store.claim(QueueName.MAIL_AUTH, Map.of(JobType.REGISTRATION_OTP_MAIL, Set.of(1)), Duration.ofSeconds(30)).orElseThrow();
        assertThat(lease.jobId()).isEqualTo(id);
        assertThat(lease.secret()).isNotNull();
        assertThat(lease.payloadJson()).doesNotContain("private-marker");
        assertThat(new MailPayloadCodec(mapper).decode(lease.type(), lease.payloadJson()).generation()).isEqualTo(first.generation());
        assertThat(store.isLeaseCurrent(lease)).isTrue();
        assertThat(store.complete(lease, JobState.SKIPPED, "INVALID_INPUT")).isTrue();
        assertThat(jdbc.queryForMap("select secret_key_id,secret_nonce,secret_ciphertext from background_jobs where id=?", id))
                .allSatisfy((key, value) -> assertThat(value).isNull());
    }
}

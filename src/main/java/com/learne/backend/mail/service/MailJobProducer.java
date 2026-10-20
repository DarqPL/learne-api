package com.learne.backend.mail.service;

import com.learne.backend.jobs.crypto.JobPayloadCipher;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobEnqueuer;
import com.learne.backend.mail.model.MailPayload;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Installed explicitly by the mail phase configuration, never by component scanning. */
public class MailJobProducer {
    private final JobEnqueuer enqueuer;
    private final JobPayloadCipher cipher;
    private final MailPayloadCodec codec;

    public MailJobProducer(JobEnqueuer enqueuer, JobPayloadCipher cipher, ObjectMapper mapper) {
        cipher.requireActiveKey();
        this.enqueuer = enqueuer; this.cipher = cipher; this.codec = new MailPayloadCodec(mapper);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(JobType type, String dedupeKey, MailPayload payload, byte[] secret) {
        var metadata = codec.canonicalize(type, payload);
        boolean auth = MailPayloadCodec.requiresSecret(metadata.template());
        if (auth ? secret == null || secret.length == 0 || secret.length > 4096 : secret != null)
            throw new IllegalArgumentException("Invalid mail secret");
        if (dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > 255 || dedupeKey.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid mail dedupe key");
        UUID id = UUID.randomUUID();
        byte[] owned = secret == null ? null : secret.clone();
        try {
            var encrypted = auth ? cipher.encrypt(id, type, metadata.generation(), owned) : null;
            return enqueuer.enqueue(new JobSpec(id, auth ? QueueName.MAIL_AUTH : QueueName.MAIL_NOTICE, type,
                    1, type == JobType.TEACHER_INVITATION_MAIL ? 1 : 0, dedupeKey, codec.encode(metadata),
                    metadata.expiresAt(), encrypted));
        } finally { if (owned != null) Arrays.fill(owned, (byte) 0); }
    }
}

package com.learne.backend.mail.service;

import com.learne.backend.jobs.crypto.JobPayloadCipher;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.mail.model.*;
import java.util.Arrays;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One exact type/version registration. Durable results always use the queue's ownership fence. */
public final class MailJobHandler implements JobHandler {
    private final JobType type;
    private final JobStore store;
    private final JobPayloadCipher cipher;
    private final MailPayloadCodec codec;
    private final MailEligibilityService eligibility;
    private final MailTemplateRenderer renderer;
    private final MailTransport transport;

    public MailJobHandler(JobType type, JobStore store, JobPayloadCipher cipher, MailPayloadCodec codec,
                          MailEligibilityService eligibility, MailTemplateRenderer renderer, MailTransport transport) {
        if (Arrays.stream(MailTemplate.values()).noneMatch(template -> template.jobType() == type))
            throw new IllegalArgumentException("Invalid mail handler type");
        this.type = type; this.store = store; this.cipher = cipher; this.codec = codec;
        this.eligibility = eligibility; this.renderer = renderer; this.transport = transport;
    }

    @Override public JobType type() { return type; }
    @Override public int payloadVersion() { return 1; }

    @Override public void execute(JobLease lease) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Mail execution requires no ambient transaction");
        if (!current(lease)) return;
        MailPayload payload;
        try {
            if (lease.type() != type || lease.payloadVersion() != payloadVersion() || lease.terminalizeOnly())
                throw new IllegalArgumentException();
            payload = codec.decode(type, lease.payloadJson());
        } catch (IllegalArgumentException ignored) { throw permanent(JobErrorCode.INVALID_INPUT); }
        if (skip(lease, eligibility.check(payload))) return;

        byte[] plaintext = null;
        try {
            boolean auth = MailPayloadCodec.requiresSecret(payload.template());
            if (auth != (lease.secret() != null)) throw permanent(JobErrorCode.INVALID_INPUT);
            if (auth) {
                if (!current(lease)) return;
                try { plaintext = cipher.decrypt(lease.jobId(), type, payload.generation(), lease.secret()); }
                catch (SecurityException ignored) { throw permanent(JobErrorCode.PAYLOAD_DECRYPTION_FAILED); }
            }
            if (payload.template() == MailTemplate.TEACHER_INVITATION
                    && skip(lease, eligibility.checkInvitationPassword(payload, plaintext))) return;
            org.springframework.mail.SimpleMailMessage message;
            try { message = renderer.render(payload, plaintext); }
            catch (IllegalArgumentException ignored) { throw permanent(JobErrorCode.INVALID_INPUT); }
            // Rendering/decryption can consume time: refresh eligibility and expiry immediately before admission.
            if (skip(lease, eligibility.check(payload))) return;
            if (!current(lease)) return;
            // No transaction survives any of the service/store calls above.
            transport.send(message);
            if (!Thread.currentThread().isInterrupted()) store.complete(lease, JobState.SUCCEEDED, null);
        } finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
    }

    private boolean current(JobLease lease) {
        return !Thread.currentThread().isInterrupted() && store.isLeaseCurrent(lease) && !Thread.currentThread().isInterrupted();
    }

    private boolean skip(JobLease lease, MailEligibilityService.Result result) {
        if (result == MailEligibilityService.Result.ALLOW) return false;
        var terminal = type == JobType.TEACHER_INVITATION_MAIL && result == MailEligibilityService.Result.CODE_EXPIRED
                ? JobState.DEAD : JobState.SKIPPED;
        if (!Thread.currentThread().isInterrupted()) store.complete(lease, terminal, result.name());
        return true;
    }

    @Override public void failPermanently(JobLease lease, String code) {
        JobErrorCode approved;
        try { approved = JobErrorCode.valueOf(code); }
        catch (IllegalArgumentException | NullPointerException ignored) { approved = JobErrorCode.INVALID_INPUT; }
        var state = approved == JobErrorCode.JOB_EXPIRED
                && (type == JobType.REGISTRATION_OTP_MAIL || type == JobType.PASSWORD_RESET_MAIL)
                ? JobState.SKIPPED : JobState.DEAD;
        if (!Thread.currentThread().isInterrupted()) store.complete(lease, state, approved.name());
    }

    private static PermanentJobException permanent(JobErrorCode code) { return new PermanentJobException(code.name()); }
}

package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.model.JobLease;
import java.util.UUID;

/** C7 owns atomic domain + job completion/failure, notifications and unlock, with current fencing.
 * No production implementation exists in C6; registration must remain disabled until C7 lands.
 */
public interface SubmissionCompletionPort {
    void complete(JobLease lease, UUID submissionId);
    void fail(JobLease lease, UUID submissionId, String code);
}

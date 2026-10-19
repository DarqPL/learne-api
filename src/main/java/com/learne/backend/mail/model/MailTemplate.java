package com.learne.backend.mail.model;

import com.learne.backend.jobs.model.JobType;

public enum MailTemplate {
    REGISTRATION_OTP(JobType.REGISTRATION_OTP_MAIL),
    PASSWORD_RESET(JobType.PASSWORD_RESET_MAIL),
    TEACHER_INVITATION(JobType.TEACHER_INVITATION_MAIL),
    PAYMENT_CONFIRMATION(JobType.PAYMENT_CONFIRMATION_MAIL),
    COURSE_PENDING_REVIEW(JobType.COURSE_PENDING_REVIEW_MAIL),
    COURSE_REVIEW_RESULT(JobType.COURSE_REVIEW_RESULT_MAIL);

    private final JobType jobType;

    MailTemplate(JobType jobType) {
        this.jobType = jobType;
    }

    public JobType jobType() {
        return jobType;
    }
}

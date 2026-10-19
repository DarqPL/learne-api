ALTER TABLE registration_otps ADD COLUMN mail_generation uuid;
ALTER TABLE users ADD COLUMN invitation_generation uuid;

-- Historical scalar IDs deliberately have no foreign keys: decisions survive domain deletion.
CREATE TABLE course_review_decisions (
    id uuid PRIMARY KEY,
    revision_id bigint NOT NULL,
    course_id bigint NOT NULL,
    submission_id uuid,
    reviewer_id bigint NOT NULL,
    teacher_id bigint NOT NULL,
    decision varchar(16) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    course_name varchar(255) NOT NULL,
    recipient_email varchar(255) NOT NULL,
    rejection_reason text,
    reviewed_at timestamptz NOT NULL,
    CONSTRAINT course_review_rejection_reason_check CHECK (decision <> 'REJECTED' OR rejection_reason IS NOT NULL)
);

CREATE UNIQUE INDEX uq_review_decision_submission ON course_review_decisions(submission_id)
    WHERE submission_id IS NOT NULL;
CREATE INDEX idx_review_decision_revision ON course_review_decisions(revision_id, reviewed_at, id);
CREATE INDEX idx_invitation_mail_recipient_generation ON background_jobs
    ((payload->>'recipientUserId'), (payload->>'generation'), created_at DESC, id DESC)
    WHERE type = 'TEACHER_INVITATION_MAIL';

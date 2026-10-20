-- H2 compatibility only; native PostgreSQL tests prove partial uniqueness.
CREATE TABLE course_submission_attempts (
    id uuid PRIMARY KEY,
    course_id bigint NOT NULL,
    revision_id bigint NOT NULL,
    teacher_id bigint NOT NULL,
    revision_number integer NOT NULL,
    course_name varchar(255) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    original_revision_status varchar(20) NOT NULL CHECK (original_revision_status IN ('DRAFT','REJECTED')),
    state varchar(20) NOT NULL CHECK (state IN ('QUEUED','PROCESSING','SUCCEEDED','FAILED')),
    policy_version varchar(64) NOT NULL,
    prompt_version varchar(32) NOT NULL,
    response_version varchar(32) NOT NULL,
    total_questions integer NOT NULL CHECK (total_questions BETWEEN 1 AND 1000),
    processed_questions integer NOT NULL DEFAULT 0,
    failure_code varchar(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CHECK (processed_questions BETWEEN 0 AND total_questions),
    CHECK ((state IN ('SUCCEEDED','FAILED')) = (completed_at IS NOT NULL))
);
CREATE INDEX idx_course_submission_active ON course_submission_attempts(course_id,state);
CREATE INDEX idx_course_submission_latest ON course_submission_attempts(course_id,created_at DESC,id DESC);
CREATE INDEX idx_course_submission_terminal ON course_submission_attempts(completed_at,id);
CREATE TABLE course_submission_questions (
    submission_id uuid NOT NULL REFERENCES course_submission_attempts(id) ON DELETE CASCADE,
    question_revision_id bigint NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    input_fingerprint varchar(64) NOT NULL,
    knowledge_point varchar(64),
    difficulty varchar(8),
    xp_reward integer,
    PRIMARY KEY (submission_id,question_revision_id),
    UNIQUE (submission_id,ordinal),
    CHECK ((knowledge_point IS NULL AND difficulty IS NULL AND xp_reward IS NULL) OR
           (knowledge_point IS NOT NULL AND knowledge_point <> '' AND difficulty IS NOT NULL AND xp_reward IS NOT NULL AND
            ((difficulty = 'easy' AND xp_reward = 10) OR (difficulty = 'medium' AND xp_reward = 20) OR
             (difficulty = 'hard' AND xp_reward = 30))))
);
CREATE TABLE user_notifications (
    id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type varchar(64) NOT NULL CHECK (type IN ('COURSE_SUBMISSION_SUCCEEDED','COURSE_SUBMISSION_FAILED')),
    submission_id uuid,
    course_id bigint,
    revision_id bigint,
    dedupe_key varchar(255) NOT NULL UNIQUE,
    message text NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_user_notifications_page ON user_notifications(user_id,created_at DESC,id DESC);
CREATE INDEX idx_user_notifications_unread ON user_notifications(user_id,read_at,created_at,id);
CREATE INDEX idx_user_notifications_retention ON user_notifications(created_at,id);

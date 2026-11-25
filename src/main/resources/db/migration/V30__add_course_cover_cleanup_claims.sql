CREATE TABLE course_cover_cleanup_claims (
    public_id VARCHAR(255) PRIMARY KEY,
    course_id BIGINT NOT NULL,
    url VARCHAR(1024),
    status VARCHAR(20) NOT NULL,
    job_id UUID,
    last_error_code VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_course_cover_cleanup_claim_status
    ON course_cover_cleanup_claims(status);

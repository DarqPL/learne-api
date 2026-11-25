ALTER TABLE user_notifications DROP CONSTRAINT user_notifications_type_check;

ALTER TABLE user_notifications
    ADD CONSTRAINT user_notifications_type_check
        CHECK (type IN (
            'COURSE_SUBMISSION_SUCCEEDED',
            'COURSE_SUBMISSION_FAILED',
            'COURSE_COVER_REVIEW_APPROVED',
            'COURSE_COVER_REVIEW_REJECTED',
            'COURSE_COVER_REVIEW_SUPERSEDED',
            'COURSE_REVISION_COVER_APPROVED',
            'COURSE_REVISION_COVER_REJECTED'
        ));

CREATE TABLE catalog_cache_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    version BIGINT NOT NULL CHECK (version >= 1)
);

INSERT INTO catalog_cache_state (id, version) VALUES (1, 1);

CREATE TABLE catalog_invalidation_outbox (
    id UUID PRIMARY KEY,
    course_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSED', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT catalog_invalidation_outbox_processed_check
        CHECK ((status = 'PROCESSED') = (processed_at IS NOT NULL))
);

CREATE INDEX idx_catalog_invalidation_outbox_pending
    ON catalog_invalidation_outbox(status, created_at, id);

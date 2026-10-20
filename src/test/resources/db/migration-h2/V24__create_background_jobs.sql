-- Startup compatibility only: native PostgreSQL tests prove queue constraints and indexes.
CREATE TABLE background_jobs (
    id uuid PRIMARY KEY,
    queue varchar(20) NOT NULL,
    type varchar(64) NOT NULL,
    payload_version integer NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'READY',
    priority smallint NOT NULL DEFAULT 0,
    dedupe_key varchar(255) NOT NULL UNIQUE,
    payload JSON NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE,
    lease_token uuid,
    lease_until TIMESTAMP WITH TIME ZONE,
    secret_key_id varchar(64),
    secret_nonce bytea,
    secret_ciphertext bytea,
    last_error_code varchar(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_background_jobs_due ON background_jobs(queue, priority, available_at, created_at, id);
CREATE INDEX idx_background_jobs_running ON background_jobs(lease_until, id);

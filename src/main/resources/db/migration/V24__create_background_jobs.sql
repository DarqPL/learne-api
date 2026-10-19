CREATE TABLE background_jobs (
    id uuid PRIMARY KEY,
    queue varchar(20) NOT NULL CHECK (queue IN ('AI', 'MAIL_AUTH', 'MAIL_NOTICE', 'MEDIA')),
    type varchar(64) NOT NULL,
    payload_version integer NOT NULL CHECK (payload_version > 0),
    state varchar(20) NOT NULL DEFAULT 'READY' CHECK (state IN ('READY', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'SKIPPED', 'DEAD')),
    priority smallint NOT NULL DEFAULT 0,
    dedupe_key varchar(255) NOT NULL UNIQUE,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts > 0),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    secret_key_id varchar(64),
    secret_nonce bytea,
    secret_ciphertext bytea,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    CONSTRAINT background_jobs_lease_check CHECK (
        (state = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT background_jobs_secret_check CHECK (
        (secret_key_id IS NULL AND secret_nonce IS NULL AND secret_ciphertext IS NULL)
        OR (secret_key_id IS NOT NULL AND secret_nonce IS NOT NULL AND octet_length(secret_nonce) = 12 AND secret_ciphertext IS NOT NULL)),
    CONSTRAINT background_jobs_completion_check CHECK (
        (state IN ('SUCCEEDED', 'SKIPPED', 'DEAD') AND completed_at IS NOT NULL)
        OR (state NOT IN ('SUCCEEDED', 'SKIPPED', 'DEAD') AND completed_at IS NULL))
);

CREATE INDEX idx_background_jobs_due ON background_jobs(queue, priority, available_at, created_at, id)
    WHERE state IN ('READY', 'RETRY_WAIT');
CREATE INDEX idx_background_jobs_running ON background_jobs(lease_until, id) WHERE state = 'RUNNING';
CREATE INDEX idx_background_jobs_expiry ON background_jobs(expires_at, id) WHERE secret_ciphertext IS NOT NULL;
CREATE INDEX idx_background_jobs_terminal ON background_jobs(completed_at, id) WHERE state IN ('SUCCEEDED', 'SKIPPED', 'DEAD');

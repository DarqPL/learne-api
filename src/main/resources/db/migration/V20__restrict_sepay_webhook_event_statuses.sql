ALTER TABLE sepay_webhook_events
    DROP CONSTRAINT sepay_webhook_events_status_check;

UPDATE sepay_webhook_events
SET status = 'IGNORED',
    failure_reason = COALESCE(failure_reason, 'Status normalized during SEPAY webhook status restriction migration.'),
    processed_at = COALESCE(processed_at, received_at)
WHERE status = 'RECEIVED';

ALTER TABLE sepay_webhook_events
    ADD CONSTRAINT sepay_webhook_events_status_check
        CHECK (status IN ('PROCESSED', 'DUPLICATE', 'UNMATCHED', 'IGNORED', 'FAILED'));

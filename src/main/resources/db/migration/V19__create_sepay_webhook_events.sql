CREATE TABLE sepay_webhook_events (
    id BIGSERIAL PRIMARY KEY,
    sepay_transaction_id BIGINT NOT NULL,
    payment_reference VARCHAR(120),
    order_id BIGINT,
    transfer_type VARCHAR(16) NOT NULL,
    transfer_amount_vnd BIGINT NOT NULL,
    gateway VARCHAR(120),
    account_number VARCHAR(64),
    reference_code VARCHAR(120),
    transaction_date TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(500),
    raw_payload TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT sepay_webhook_events_transaction_unique UNIQUE (sepay_transaction_id),
    CONSTRAINT sepay_webhook_events_order_id_fk FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL,
    CONSTRAINT sepay_webhook_events_amount_non_negative_check CHECK (transfer_amount_vnd >= 0),
    CONSTRAINT sepay_webhook_events_transfer_type_check CHECK (transfer_type IN ('in', 'out')),
    CONSTRAINT sepay_webhook_events_status_check CHECK (status IN ('RECEIVED', 'PROCESSED', 'DUPLICATE', 'UNMATCHED', 'IGNORED', 'FAILED'))
);

CREATE INDEX idx_sepay_webhook_events_payment_reference
    ON sepay_webhook_events(payment_reference);
CREATE INDEX idx_sepay_webhook_events_order_id
    ON sepay_webhook_events(order_id);
CREATE INDEX idx_sepay_webhook_events_status_received_at
    ON sepay_webhook_events(status, received_at);

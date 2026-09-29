ALTER TABLE orders
    ADD COLUMN dedupe_key VARCHAR(120);

CREATE UNIQUE INDEX uq_orders_dedupe_key ON orders(dedupe_key);

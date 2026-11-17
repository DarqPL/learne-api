ALTER TABLE orders
    ADD COLUMN public_id UUID;

UPDATE orders
SET public_id = gen_random_uuid()
WHERE public_id IS NULL;

ALTER TABLE orders
    ALTER COLUMN public_id SET NOT NULL;

ALTER TABLE orders
    ADD CONSTRAINT orders_public_id_key UNIQUE (public_id);

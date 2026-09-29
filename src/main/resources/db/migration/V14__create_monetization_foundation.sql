ALTER TABLE users
    ADD COLUMN account_origin VARCHAR(32) NOT NULL DEFAULT 'SELF_REGISTERED';

ALTER TABLE users
    ADD COLUMN owner_teacher_id BIGINT;

ALTER TABLE users
    ADD CONSTRAINT users_account_origin_check
        CHECK (account_origin IN ('SELF_REGISTERED', 'TEACHER_CREATED', 'ADMIN_CREATED'));

ALTER TABLE users
    ADD CONSTRAINT users_owner_teacher_id_fk
        FOREIGN KEY (owner_teacher_id) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_users_owner_teacher_id ON users(owner_teacher_id);

ALTER TABLE courses
    ADD COLUMN pricing_type VARCHAR(16) NOT NULL DEFAULT 'FREE';

ALTER TABLE courses
    ADD COLUMN price_amount_vnd BIGINT;

ALTER TABLE courses
    ADD COLUMN proposed_price_amount_vnd BIGINT;

ALTER TABLE courses
    ADD COLUMN price_status VARCHAR(32) NOT NULL DEFAULT 'NONE';

ALTER TABLE courses
    ADD CONSTRAINT courses_pricing_type_check
        CHECK (pricing_type IN ('FREE', 'PAID'));

ALTER TABLE courses
    ADD CONSTRAINT courses_price_status_check
        CHECK (price_status IN ('NONE', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'));

ALTER TABLE courses
    ADD CONSTRAINT courses_price_amount_non_negative_check
        CHECK (price_amount_vnd IS NULL OR price_amount_vnd >= 0);

ALTER TABLE courses
    ADD CONSTRAINT courses_proposed_price_amount_non_negative_check
        CHECK (proposed_price_amount_vnd IS NULL OR proposed_price_amount_vnd >= 0);

ALTER TABLE course_revisions
    ADD COLUMN pricing_type VARCHAR(16) NOT NULL DEFAULT 'FREE';

ALTER TABLE course_revisions
    ADD COLUMN proposed_price_amount_vnd BIGINT;

ALTER TABLE course_revisions
    ADD CONSTRAINT course_revisions_pricing_type_check
        CHECK (pricing_type IN ('FREE', 'PAID'));

ALTER TABLE course_revisions
    ADD CONSTRAINT course_revisions_proposed_price_amount_non_negative_check
        CHECK (proposed_price_amount_vnd IS NULL OR proposed_price_amount_vnd >= 0);

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    original_amount_vnd BIGINT NOT NULL,
    discount_amount_vnd BIGINT NOT NULL DEFAULT 0,
    final_amount_vnd BIGINT NOT NULL,
    coupon_id BIGINT,
    payment_provider VARCHAR(32) NOT NULL,
    payment_reference VARCHAR(120),
    paid_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT orders_user_id_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT orders_order_type_check CHECK (order_type IN ('COURSE_PURCHASE')),
    CONSTRAINT orders_status_check CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT orders_payment_provider_check CHECK (payment_provider IN ('INTERNAL', 'SEPAY')),
    CONSTRAINT orders_amounts_non_negative_check CHECK (
        original_amount_vnd >= 0 AND discount_amount_vnd >= 0 AND final_amount_vnd >= 0
    )
);

CREATE INDEX idx_orders_user_created_at ON orders(user_id, created_at, id);
CREATE INDEX idx_orders_status_expires_at ON orders(status, expires_at);
CREATE UNIQUE INDEX uq_orders_payment_reference ON orders(payment_reference);

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_type VARCHAR(32) NOT NULL,
    course_id BIGINT,
    item_name_snapshot VARCHAR(255) NOT NULL,
    unit_amount_vnd BIGINT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    total_amount_vnd BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT order_items_order_id_fk FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT order_items_course_id_fk FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE RESTRICT,
    CONSTRAINT order_items_product_type_check CHECK (product_type IN ('COURSE')),
    CONSTRAINT order_items_course_required_check CHECK (product_type <> 'COURSE' OR course_id IS NOT NULL),
    CONSTRAINT order_items_amounts_non_negative_check CHECK (
        unit_amount_vnd >= 0 AND quantity > 0 AND total_amount_vnd >= 0
    )
);

CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_course_id ON order_items(course_id);

CREATE TABLE course_entitlements (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    source VARCHAR(32) NOT NULL,
    order_id BIGINT,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    ends_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT course_entitlements_user_id_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT course_entitlements_course_id_fk FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
    CONSTRAINT course_entitlements_order_id_fk FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL,
    CONSTRAINT course_entitlements_source_check CHECK (source IN ('COURSE_PURCHASE', 'TEACHER_CREATED_ACCOUNT')),
    CONSTRAINT course_entitlements_time_range_check CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX idx_course_entitlements_user_course ON course_entitlements(user_id, course_id);
CREATE INDEX idx_course_entitlements_order_id ON course_entitlements(order_id);
CREATE INDEX idx_course_entitlements_active_lookup
    ON course_entitlements(user_id, course_id, starts_at, ends_at, revoked_at);

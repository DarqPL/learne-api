CREATE TABLE coupons (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    code_normalized VARCHAR(64) NOT NULL,
    discount_type VARCHAR(32) NOT NULL,
    discount_value BIGINT NOT NULL,
    min_order_amount_vnd BIGINT NOT NULL DEFAULT 0,
    max_discount_amount_vnd BIGINT,
    minimum_final_amount_vnd BIGINT NOT NULL DEFAULT 0,
    usage_limit_total INTEGER,
    usage_limit_per_user INTEGER,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT coupons_created_by_fk FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT coupons_code_normalized_unique UNIQUE (code_normalized),
    CONSTRAINT coupons_discount_type_check CHECK (discount_type IN ('PERCENT', 'FIXED_AMOUNT')),
    CONSTRAINT coupons_discount_value_positive_check CHECK (discount_value > 0),
    CONSTRAINT coupons_percent_range_check CHECK (discount_type <> 'PERCENT' OR discount_value <= 100),
    CONSTRAINT coupons_amounts_non_negative_check CHECK (
        min_order_amount_vnd >= 0
        AND (max_discount_amount_vnd IS NULL OR max_discount_amount_vnd >= 0)
        AND minimum_final_amount_vnd >= 0
    ),
    CONSTRAINT coupons_usage_limits_positive_check CHECK (
        (usage_limit_total IS NULL OR usage_limit_total > 0)
        AND (usage_limit_per_user IS NULL OR usage_limit_per_user > 0)
    ),
    CONSTRAINT coupons_valid_window_check CHECK (valid_until > valid_from)
);

CREATE TABLE coupon_product_scopes (
    coupon_id BIGINT NOT NULL,
    product_scope VARCHAR(32) NOT NULL,
    CONSTRAINT coupon_product_scopes_coupon_id_fk FOREIGN KEY (coupon_id) REFERENCES coupons(id) ON DELETE CASCADE,
    CONSTRAINT coupon_product_scopes_product_scope_check CHECK (product_scope IN ('COURSE_PURCHASE', 'VIP', 'PRO', 'PRO_PLUS')),
    CONSTRAINT coupon_product_scopes_pk PRIMARY KEY (coupon_id, product_scope)
);

CREATE TABLE coupon_course_scopes (
    coupon_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    CONSTRAINT coupon_course_scopes_coupon_id_fk FOREIGN KEY (coupon_id) REFERENCES coupons(id) ON DELETE CASCADE,
    CONSTRAINT coupon_course_scopes_course_id_fk FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
    CONSTRAINT coupon_course_scopes_pk PRIMARY KEY (coupon_id, course_id)
);

CREATE TABLE coupon_teacher_scopes (
    coupon_id BIGINT NOT NULL,
    teacher_id BIGINT NOT NULL,
    CONSTRAINT coupon_teacher_scopes_coupon_id_fk FOREIGN KEY (coupon_id) REFERENCES coupons(id) ON DELETE CASCADE,
    CONSTRAINT coupon_teacher_scopes_teacher_id_fk FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT coupon_teacher_scopes_pk PRIMARY KEY (coupon_id, teacher_id)
);

CREATE TABLE coupon_redemptions (
    id BIGSERIAL PRIMARY KEY,
    coupon_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    discount_amount_vnd BIGINT NOT NULL,
    redeemed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT coupon_redemptions_coupon_id_fk FOREIGN KEY (coupon_id) REFERENCES coupons(id) ON DELETE RESTRICT,
    CONSTRAINT coupon_redemptions_user_id_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT coupon_redemptions_order_id_fk FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT coupon_redemptions_order_unique UNIQUE (order_id),
    CONSTRAINT coupon_redemptions_discount_non_negative_check CHECK (discount_amount_vnd >= 0)
);

ALTER TABLE orders
    ADD CONSTRAINT orders_coupon_id_fk FOREIGN KEY (coupon_id) REFERENCES coupons(id) ON DELETE SET NULL;

CREATE INDEX idx_coupons_active_validity
    ON coupons(active, valid_from, valid_until);
CREATE INDEX idx_coupons_created_by
    ON coupons(created_by);
CREATE INDEX idx_coupon_course_scopes_course_id
    ON coupon_course_scopes(course_id);
CREATE INDEX idx_coupon_teacher_scopes_teacher_id
    ON coupon_teacher_scopes(teacher_id);
CREATE INDEX idx_coupon_redemptions_coupon_user
    ON coupon_redemptions(coupon_id, user_id);
CREATE INDEX idx_coupon_redemptions_user_id
    ON coupon_redemptions(user_id);
CREATE INDEX idx_coupon_redemptions_redeemed_at
    ON coupon_redemptions(redeemed_at);
CREATE INDEX idx_orders_coupon_id
    ON orders(coupon_id);
CREATE INDEX idx_orders_paid_type_paid_at
    ON orders(status, order_type, paid_at);
CREATE INDEX idx_order_items_plan_code
    ON order_items(plan_code);
CREATE INDEX idx_lesson_progress_status_completed_at
    ON lesson_progress(status, completed_at);
CREATE INDEX idx_quiz_attempts_status_submitted_at
    ON quiz_attempts(status, submitted_at);

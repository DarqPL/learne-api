ALTER TABLE order_items
    ADD COLUMN plan_code VARCHAR(32);

ALTER TABLE orders
    DROP CONSTRAINT orders_order_type_check;
ALTER TABLE orders
    ADD CONSTRAINT orders_order_type_check CHECK (order_type IN ('COURSE_PURCHASE', 'PLAN_SUBSCRIPTION'));

ALTER TABLE order_items
    DROP CONSTRAINT order_items_product_type_check;
ALTER TABLE order_items
    ADD CONSTRAINT order_items_product_type_check CHECK (product_type IN ('COURSE', 'PLAN'));

ALTER TABLE order_items
    DROP CONSTRAINT order_items_course_required_check;
ALTER TABLE order_items
    ADD CONSTRAINT order_items_product_reference_check CHECK (
        (product_type = 'COURSE' AND course_id IS NOT NULL AND plan_code IS NULL)
        OR (product_type = 'PLAN' AND plan_code IS NOT NULL AND course_id IS NULL)
    );

ALTER TABLE course_entitlements
    ADD COLUMN subscription_id BIGINT;

CREATE TABLE subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    plan_code VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    order_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT subscriptions_user_id_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT subscriptions_order_id_fk FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT subscriptions_plan_code_check CHECK (plan_code IN ('VIP', 'PRO', 'PRO_PLUS')),
    CONSTRAINT subscriptions_status_check CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT subscriptions_time_range_check CHECK (ends_at > starts_at)
);

CREATE INDEX idx_subscriptions_user_status_ends_at
    ON subscriptions(user_id, status, ends_at);
CREATE INDEX idx_subscriptions_plan_ends_at
    ON subscriptions(plan_code, ends_at);
CREATE INDEX idx_subscriptions_order_id
    ON subscriptions(order_id);

ALTER TABLE course_entitlements
    ADD CONSTRAINT course_entitlements_subscription_id_fk
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE SET NULL;

CREATE INDEX idx_course_entitlements_subscription_id
    ON course_entitlements(subscription_id);
CREATE INDEX idx_course_entitlements_user_source_expires_at
    ON course_entitlements(user_id, source, ends_at);

CREATE TABLE vip_course_selections (
    id BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    level VARCHAR(50) NOT NULL,
    selected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    replaced_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT vip_course_selections_subscription_id_fk FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE,
    CONSTRAINT vip_course_selections_user_id_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT vip_course_selections_course_id_fk FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE
);

CREATE INDEX idx_vip_course_selections_user_active
    ON vip_course_selections(user_id, replaced_at, level);
CREATE INDEX idx_vip_course_selections_subscription_active
    ON vip_course_selections(subscription_id, replaced_at, course_id);
CREATE UNIQUE INDEX uq_vip_course_selections_subscription_course
    ON vip_course_selections(subscription_id, course_id);

CREATE TABLE ai_usage_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    feature VARCHAR(64) NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    request_id VARCHAR(120),
    CONSTRAINT ai_usage_events_user_id_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ai_usage_events_feature_check CHECK (feature IN ('GRAMMAR_CHECK', 'AI_TUTOR', 'LEARNING_PATH', 'COURSE_RECOMMENDATION'))
);

CREATE INDEX idx_ai_usage_events_user_used_at
    ON ai_usage_events(user_id, used_at);
CREATE INDEX idx_ai_usage_events_feature_used_at
    ON ai_usage_events(feature, used_at);
CREATE UNIQUE INDEX uq_ai_usage_events_request_id
    ON ai_usage_events(request_id);

CREATE UNIQUE INDEX uq_course_entitlements_active_course_purchase
    ON course_entitlements(user_id, course_id, source);

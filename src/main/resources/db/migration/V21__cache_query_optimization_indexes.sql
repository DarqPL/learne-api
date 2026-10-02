CREATE INDEX IF NOT EXISTS idx_courses_status_published_at
    ON courses(status, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_courses_teacher_updated_at
    ON courses(teacher_id, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_courses_teacher_status
    ON courses(teacher_id, status);

CREATE INDEX IF NOT EXISTS idx_course_entitlements_user_course_active
    ON course_entitlements(user_id, course_id, revoked_at, starts_at, ends_at);

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_status_plan_active
    ON subscriptions(user_id, status, plan_code, starts_at, ends_at);

CREATE INDEX IF NOT EXISTS idx_questions_lesson_status_order
    ON questions(lesson_id, status, order_index, id);

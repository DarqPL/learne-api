CREATE INDEX IF NOT EXISTS idx_courses_status_level_published_at
    ON courses(status, level, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_courses_status_teacher_published_at
    ON courses(status, teacher_id, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_courses_status_level_teacher_published_at
    ON courses(status, level, teacher_id, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_courses_teacher_order_index_id
    ON courses(teacher_id, order_index ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_users_role_created_at_id
    ON users(role, created_at ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_course_enrollments_user_enrolled_at
    ON course_enrollments(user_id, enrolled_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_lesson_progress_user_status
    ON lesson_progress(user_id, status);

CREATE INDEX IF NOT EXISTS idx_quiz_attempts_user_submitted_at
    ON quiz_attempts(user_id, submitted_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_grammar_checks_user_created_at_id
    ON grammar_checks(user_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_ai_conversations_user_updated_at
    ON ai_conversations(user_id, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_user_stats_leaderboard
    ON user_stats(total_xp DESC, current_streak_days DESC, longest_streak_days DESC, user_id);

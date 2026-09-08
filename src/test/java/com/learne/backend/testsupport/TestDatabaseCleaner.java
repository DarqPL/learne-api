package com.learne.backend.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

public final class TestDatabaseCleaner {
    private static final String[] DELETE_STATEMENTS = {
            "delete from ai_feedback",
            "delete from ai_messages",
            "delete from ai_conversations",
            "delete from grammar_check_errors",
            "delete from grammar_checks",
            "delete from course_recommendation_items",
            "delete from course_recommendation_paths",
            "delete from placement_attempt_answers",
            "delete from placement_attempt_questions",
            "delete from placement_attempts",
            "delete from placement_preferences",
            "delete from learning_path_items",
            "delete from learning_paths",
            "delete from question_answers",
            "delete from quiz_attempts",
            "delete from learning_activities",
            "delete from user_stats",
            "delete from lesson_progress",
            "delete from course_enrollments",
            "delete from question_options",
            "delete from questions",
            "delete from lessons",
            "delete from topics",
            "delete from courses",
            "delete from teacher_profiles",
            "delete from password_reset_tokens",
            "delete from registration_otps",
            "delete from refresh_tokens",
            "delete from users"
    };

    private TestDatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("update refresh_tokens set replaced_by_token_id = null");
        for (String statement : DELETE_STATEMENTS) {
            jdbcTemplate.update(statement);
        }
    }
}

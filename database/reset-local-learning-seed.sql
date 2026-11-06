-- LOCAL/DEV ONLY.
-- Inspect the active connection and confirm it is a disposable development
-- database before running this transaction. This script preserves users,
-- authentication, grammar-check history, and commerce history.

BEGIN;

CREATE TEMP TABLE _reset_course_ids ON COMMIT DROP AS
SELECT c.id
FROM public.courses c
WHERE NOT EXISTS (
    SELECT 1
    FROM public.order_items oi
    WHERE oi.course_id = c.id
)
AND NOT EXISTS (
    SELECT 1
    FROM public.course_entitlements ce
    WHERE ce.course_id = c.id
)
AND NOT EXISTS (
    SELECT 1
    FROM public.coupon_course_scopes ccs
    WHERE ccs.course_id = c.id
)
AND NOT EXISTS (
    SELECT 1
    FROM public.vip_course_selections vcs
    WHERE vcs.course_id = c.id
);

CREATE TEMP TABLE _reset_topic_ids ON COMMIT DROP AS
SELECT id FROM public.topics WHERE course_id IN (SELECT id FROM _reset_course_ids);

CREATE TEMP TABLE _reset_lesson_ids ON COMMIT DROP AS
SELECT id FROM public.lessons WHERE topic_id IN (SELECT id FROM _reset_topic_ids);

CREATE TEMP TABLE _reset_question_ids ON COMMIT DROP AS
SELECT id FROM public.questions WHERE lesson_id IN (SELECT id FROM _reset_lesson_ids);

CREATE TEMP TABLE _reset_enrollment_ids ON COMMIT DROP AS
SELECT id FROM public.course_enrollments
WHERE course_id IN (SELECT id FROM _reset_course_ids);

CREATE TEMP TABLE _reset_quiz_attempt_ids ON COMMIT DROP AS
SELECT id FROM public.quiz_attempts
WHERE lesson_id IN (SELECT id FROM _reset_lesson_ids)
   OR course_enrollment_id IN (SELECT id FROM _reset_enrollment_ids);

CREATE TEMP TABLE _reset_learning_path_ids ON COMMIT DROP AS
SELECT DISTINCT path_id FROM public.learning_path_items
WHERE lesson_id IN (SELECT id FROM _reset_lesson_ids);

CREATE TEMP TABLE _reset_recommendation_path_ids ON COMMIT DROP AS
SELECT DISTINCT path_id FROM public.course_recommendation_items
WHERE course_id IN (SELECT id FROM _reset_course_ids);

CREATE TEMP TABLE _reset_revision_ids ON COMMIT DROP AS
SELECT id FROM public.course_revisions
WHERE course_id IN (SELECT id FROM _reset_course_ids);

CREATE TEMP TABLE _reset_lesson_revision_ids ON COMMIT DROP AS
SELECT id FROM public.lesson_revisions
WHERE topic_revision_id IN (
    SELECT id FROM public.topic_revisions
    WHERE revision_id IN (SELECT id FROM _reset_revision_ids)
);

CREATE TEMP TABLE _reset_question_revision_ids ON COMMIT DROP AS
SELECT id FROM public.question_revisions
WHERE lesson_revision_id IN (SELECT id FROM _reset_lesson_revision_ids);

DELETE FROM question_answers
WHERE question_id IN (SELECT id FROM _reset_question_ids);

DELETE FROM placement_attempt_answers
WHERE question_id IN (SELECT id FROM _reset_question_ids);

DELETE FROM placement_attempt_questions
WHERE question_id IN (SELECT id FROM _reset_question_ids);

DELETE FROM question_option_revisions
WHERE question_revision_id IN (SELECT id FROM _reset_question_revision_ids);

DELETE FROM question_revisions
WHERE id IN (SELECT id FROM _reset_question_revision_ids);

DELETE FROM learning_activities
WHERE source_type = 'LESSON_QUIZ_PASS'
  AND source_id IN (SELECT id FROM _reset_quiz_attempt_ids);

DELETE FROM lesson_progress
WHERE lesson_id IN (SELECT id FROM _reset_lesson_ids)
   OR course_enrollment_id IN (SELECT id FROM _reset_enrollment_ids);

DELETE FROM quiz_attempts
WHERE lesson_id IN (SELECT id FROM _reset_lesson_ids)
   OR course_enrollment_id IN (SELECT id FROM _reset_enrollment_ids);

DELETE FROM course_enrollments
WHERE id IN (SELECT id FROM _reset_enrollment_ids);

DELETE FROM learning_path_items
WHERE lesson_id IN (SELECT id FROM _reset_lesson_ids);

DELETE FROM learning_paths
WHERE id IN (SELECT path_id FROM _reset_learning_path_ids);

DELETE FROM course_recommendation_items
WHERE course_id IN (SELECT id FROM _reset_course_ids);

DELETE FROM course_recommendation_paths
WHERE id IN (SELECT path_id FROM _reset_recommendation_path_ids);

DELETE FROM coupon_course_scopes
WHERE course_id IN (SELECT id FROM _reset_course_ids);

DELETE FROM course_entitlements
WHERE course_id IN (SELECT id FROM _reset_course_ids);

DELETE FROM vip_course_selections
WHERE course_id IN (SELECT id FROM _reset_course_ids);

DELETE FROM course_submission_attempts
WHERE course_id IN (SELECT id FROM _reset_course_ids);

DELETE FROM course_review_decisions
WHERE course_id IN (SELECT id FROM _reset_course_ids);

DELETE FROM question_options
WHERE question_id IN (SELECT id FROM _reset_question_ids);

DELETE FROM questions
WHERE id IN (SELECT id FROM _reset_question_ids);

DELETE FROM lesson_revisions
WHERE id IN (SELECT id FROM _reset_lesson_revision_ids);

DELETE FROM lessons
WHERE id IN (SELECT id FROM _reset_lesson_ids);

DELETE FROM topic_revisions
WHERE revision_id IN (SELECT id FROM _reset_revision_ids);

DELETE FROM topics
WHERE id IN (SELECT id FROM _reset_topic_ids);

DELETE FROM course_revisions
WHERE id IN (SELECT id FROM _reset_revision_ids);

DELETE FROM courses
WHERE id IN (SELECT id FROM _reset_course_ids);

COMMIT;

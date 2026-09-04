-- Phase 8 AI course recommendation demo seed.
-- This is a manual local/dev seed, not a Flyway migration.
-- Apply from the repository root:
--   docker compose -f infra/docker-compose.dev.yml exec -T postgres psql -U learne -d learne < backend/database/seed-phase8-ai-demo.sql
-- If PowerShell redirection causes issues:
--   Get-Content backend/database/seed-phase8-ai-demo.sql | docker compose -f infra/docker-compose.dev.yml exec -T postgres psql -U learne -d learne
--
-- Demo users are created so content has valid owners/reviewers. This script is not intended
-- to document production credentials; create or reset demo account passwords through the API
-- if predictable local logins are needed.
--
-- Idempotency note: this script does not delete demo content. It updates existing demo rows
-- by stable natural fields and inserts only missing rows, so reruns do not break references
-- from placement attempts, recommendations, enrollments, lesson progress, or answers.

DO $$
DECLARE
    v_admin_id BIGINT;
    v_teacher_id BIGINT;
    v_student_id BIGINT;
    v_course_id BIGINT;
    v_topic_id BIGINT;
    v_lesson_id BIGINT;
    v_question_id BIGINT;
    v_option_id BIGINT;
    skill_name TEXT;
    demo_password_hash TEXT;
    course_name TEXT;
    topic_name TEXT;
    lesson_title TEXT;
    placement_question RECORD;
    candidate_course RECORD;
    candidate_skill_type TEXT;
    option_values TEXT[];
    option_text_value TEXT;
    option_order INT;
BEGIN
    SELECT password_hash INTO demo_password_hash
    FROM users
    WHERE email = 'admin@learne.local'
    ORDER BY id
    LIMIT 1;

    IF demo_password_hash IS NULL THEN
        -- Same dev-only fallback hash used by the existing Phase 7 manual seed.
        demo_password_hash := '$2a$10$JKjLIkGQVUOySbEeznvabu1P8/lGVdkLZKZe1.u48yWnj8PpxMAg2';
    END IF;

    INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
    VALUES ('phase8-admin@learne.local', demo_password_hash, 'Phase 8 Demo Admin', 'ADMIN', TRUE, FALSE, FALSE)
    ON CONFLICT (email) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        role = EXCLUDED.role,
        is_active = TRUE,
        is_locked = FALSE,
        must_change_password = FALSE,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO v_admin_id;

    INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
    VALUES ('phase8-teacher@learne.local', demo_password_hash, 'Phase 8 Demo Teacher', 'TEACHER', TRUE, FALSE, FALSE)
    ON CONFLICT (email) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        role = EXCLUDED.role,
        is_active = TRUE,
        is_locked = FALSE,
        must_change_password = FALSE,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO v_teacher_id;

    INSERT INTO teacher_profiles (user_id, bio, expertise, years_of_experience, status)
    VALUES (
        v_teacher_id,
        'Local demo teacher for Phase 8 AI course recommendation content.',
        'Placement diagnostics, vocabulary, grammar, reading, writing',
        6,
        'ACTIVE'
    )
    ON CONFLICT (user_id) DO UPDATE SET
        bio = EXCLUDED.bio,
        expertise = EXCLUDED.expertise,
        years_of_experience = EXCLUDED.years_of_experience,
        status = 'ACTIVE',
        updated_at = CURRENT_TIMESTAMP;

    INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
    VALUES ('phase8-student@learne.local', demo_password_hash, 'Phase 8 Demo Student', 'STUDENT', TRUE, FALSE, FALSE)
    ON CONFLICT (email) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        role = EXCLUDED.role,
        is_active = TRUE,
        is_locked = FALSE,
        must_change_password = FALSE,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO v_student_id;

    FOREACH skill_name IN ARRAY ARRAY['VOCABULARY', 'GRAMMAR', 'READING', 'WRITING'] LOOP
        course_name := 'Phase 8 AI Demo - Placement ' || initcap(lower(skill_name));
        topic_name := 'Placement ' || initcap(lower(skill_name));
        lesson_title := 'Phase 8 Placement ' || initcap(lower(skill_name));

        UPDATE courses SET
            teacher_id = v_teacher_id,
            description = 'Placement-test question pool for Phase 8 AI course recommendations: ' || lower(skill_name) || '.',
            type = skill_name,
            level = 'A1',
            order_index = 100,
            status = 'APPROVED',
            reviewed_by = v_admin_id,
            reviewed_at = CURRENT_TIMESTAMP,
            published_at = COALESCE(published_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE name = course_name
        RETURNING id INTO v_course_id;

        IF v_course_id IS NULL THEN
            INSERT INTO courses (
                teacher_id, name, description, type, level, order_index, status,
                reviewed_by, reviewed_at, published_at, created_at, updated_at
            )
            VALUES (
                v_teacher_id,
                course_name,
                'Placement-test question pool for Phase 8 AI course recommendations: ' || lower(skill_name) || '.',
                skill_name,
                'A1',
                100,
                'APPROVED',
                v_admin_id,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
            RETURNING id INTO v_course_id;
        END IF;

        UPDATE topics SET
            description = 'Approved placement questions for ' || lower(skill_name) || '.',
            order_index = 1
        WHERE topics.course_id = v_course_id AND topics.name = topic_name
        RETURNING id INTO v_topic_id;

        IF v_topic_id IS NULL THEN
            INSERT INTO topics (course_id, name, description, order_index)
            VALUES (v_course_id, topic_name, 'Approved placement questions for ' || lower(skill_name) || '.', 1)
            RETURNING id INTO v_topic_id;
        END IF;

        UPDATE lessons SET
            teacher_id = v_teacher_id,
            description = 'Approved Phase 8 placement lesson for ' || lower(skill_name) || ' diagnostics.',
            content = 'Answer these short diagnostic questions so the AI recommendation service can infer skill gaps.',
            skill_type = skill_name,
            order_index = 1,
            xp_reward = 0,
            is_locked = FALSE,
            is_placement_test = TRUE,
            status = 'APPROVED',
            reviewed_by = v_admin_id,
            reviewed_at = CURRENT_TIMESTAMP,
            published_at = COALESCE(published_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE lessons.topic_id = v_topic_id AND lessons.title = lesson_title
        RETURNING id INTO v_lesson_id;

        IF v_lesson_id IS NULL THEN
            INSERT INTO lessons (
                topic_id, teacher_id, title, description, content, skill_type, order_index,
                xp_reward, is_locked, is_placement_test, status, reviewed_by, reviewed_at, published_at, created_at, updated_at
            )
            VALUES (
                v_topic_id,
                v_teacher_id,
                lesson_title,
                'Approved Phase 8 placement lesson for ' || lower(skill_name) || ' diagnostics.',
                'Answer these short diagnostic questions so the AI recommendation service can infer skill gaps.',
                skill_name,
                1,
                0,
                FALSE,
                TRUE,
                'APPROVED',
                v_admin_id,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
            RETURNING id INTO v_lesson_id;
        END IF;

        FOR placement_question IN
            SELECT * FROM (VALUES
                ('VOCABULARY', 1, 'Choose the word that means the same as "happy".', 'joyful', 'angry', 'late', 'empty', 'joyful', 'Recognizing common adjective synonyms.'),
                ('VOCABULARY', 2, 'Choose the best word: I drink water from a ____.', 'glass', 'ticket', 'pencil', 'shoe', 'glass', 'Identifying everyday object nouns.'),
                ('VOCABULARY', 3, 'Which word names a place where students learn?', 'school', 'market', 'airport', 'hospital', 'school', 'Matching common places to their purpose.'),
                ('VOCABULARY', 4, 'Choose the opposite of "small".', 'big', 'cold', 'slow', 'quiet', 'big', 'Recognizing basic antonyms.'),
                ('VOCABULARY', 5, 'Which word completes the phrase: brush your ____?', 'teeth', 'rain', 'chair', 'map', 'teeth', 'Using common daily routine vocabulary.'),
                ('GRAMMAR', 1, 'Choose the correct sentence.', 'She likes coffee.', 'She like coffee.', 'She liking coffee.', 'She to like coffee.', 'She likes coffee.', 'Present simple third-person agreement.'),
                ('GRAMMAR', 2, 'Choose the correct question.', 'Do you work on Monday?', 'Does you work on Monday?', 'Are you work on Monday?', 'You do work on Monday?', 'Do you work on Monday?', 'Forming present simple questions with do.'),
                ('GRAMMAR', 3, 'Choose the correct negative sentence.', 'I do not speak French.', 'I does not speak French.', 'I not speak French.', 'I am not speak French.', 'I do not speak French.', 'Forming present simple negatives.'),
                ('GRAMMAR', 4, 'Choose the correct be-verb sentence.', 'They are students.', 'They is students.', 'They am students.', 'They be students.', 'They are students.', 'Using be with plural subjects.'),
                ('GRAMMAR', 5, 'Choose the correct article.', 'I have an umbrella.', 'I have a umbrella.', 'I have the umbrella yesterday.', 'I have umbrella a.', 'I have an umbrella.', 'Using a and an before vowel sounds.'),
                ('READING', 1, 'Read: "Lan meets Nam at 8 a.m." When do they meet?', 'At 8 a.m.', 'At night', 'On Sunday', 'At school', 'At 8 a.m.', 'Finding explicit time information.'),
                ('READING', 2, 'Read: "Tom buys bread because he is hungry." Why does Tom buy bread?', 'Because he is hungry', 'Because he is late', 'Because it is raining', 'Because he has homework', 'Because he is hungry', 'Identifying a simple reason in a sentence.'),
                ('READING', 3, 'Read: "The library is next to the bank." Where is the library?', 'Next to the bank', 'Behind the airport', 'Inside the school', 'Across from the park', 'Next to the bank', 'Understanding location phrases.'),
                ('READING', 4, 'Read: "Mai likes tea, but her brother likes milk." What does Mai like?', 'Tea', 'Milk', 'Coffee', 'Juice', 'Tea', 'Tracking subject-specific details.'),
                ('READING', 5, 'Read: "The shop opens at nine and closes at five." When does the shop close?', 'At five', 'At nine', 'At noon', 'At seven', 'At five', 'Finding explicit schedule information.'),
                ('WRITING', 1, 'Choose the best complete sentence.', 'I live in Hanoi.', 'Live I in Hanoi.', 'I in Hanoi live.', 'Hanoi I live in.', 'I live in Hanoi.', 'Writing basic subject-verb-place sentences.'),
                ('WRITING', 2, 'Choose the sentence with correct capitalization.', 'My name is Linh.', 'my name is Linh.', 'My name is linh.', 'my Name is linh.', 'My name is Linh.', 'Using capital letters in simple sentences.'),
                ('WRITING', 3, 'Choose the correctly punctuated sentence.', 'Where are you from?', 'Where are you from.', 'Where are you from', 'Where are you from!', 'Where are you from?', 'Using question marks for questions.'),
                ('WRITING', 4, 'Choose the best word order.', 'She goes to school every day.', 'Every day school to she goes.', 'Goes she to school every day.', 'To school every day she goes.', 'She goes to school every day.', 'Ordering words in common present simple sentences.'),
                ('WRITING', 5, 'Choose the best sentence for an email opening.', 'Dear Ms. Hoa,', 'Bye Ms. Hoa,', 'At Ms. Hoa,', 'Then Ms. Hoa,', 'Dear Ms. Hoa,', 'Choosing an appropriate short writing convention.')
            ) AS q(skill, order_index, question_text, option_a, option_b, option_c, option_d, correct_option, explanation)
            WHERE q.skill = skill_name
            ORDER BY q.order_index
        LOOP
            UPDATE questions SET
                teacher_id = v_teacher_id,
                type = 'MULTIPLE_CHOICE',
                explanation = placement_question.explanation,
                correct_answer = NULL,
                knowledge_point = lower(skill_name),
                order_index = placement_question.order_index,
                difficulty = 'A1',
                xp_reward = 0,
                status = 'APPROVED',
                reviewed_by = v_admin_id,
                reviewed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE questions.lesson_id = v_lesson_id AND questions.question_text = placement_question.question_text
            RETURNING id INTO v_question_id;

            IF v_question_id IS NULL THEN
                INSERT INTO questions (
                    lesson_id, teacher_id, type, question_text, explanation, correct_answer,
                    knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at
                )
                VALUES (
                    v_lesson_id,
                    v_teacher_id,
                    'MULTIPLE_CHOICE',
                    placement_question.question_text,
                    placement_question.explanation,
                    NULL,
                    lower(skill_name),
                    placement_question.order_index,
                    'A1',
                    0,
                    'APPROVED',
                    v_admin_id,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                RETURNING id INTO v_question_id;
            END IF;

            option_values := ARRAY[
                placement_question.option_a,
                placement_question.option_b,
                placement_question.option_c,
                placement_question.option_d
            ];

            FOR option_order IN 1..4 LOOP
                option_text_value := option_values[option_order];
                v_option_id := NULL;

                SELECT id INTO v_option_id
                FROM question_options
                WHERE question_options.question_id = v_question_id AND question_options.order_index = option_order
                ORDER BY id
                LIMIT 1;

                IF v_option_id IS NULL THEN
                    INSERT INTO question_options (question_id, option_text, is_correct, order_index)
                    VALUES (v_question_id, option_text_value, option_text_value = placement_question.correct_option, option_order);
                ELSE
                    UPDATE question_options SET
                        option_text = option_text_value,
                        is_correct = option_text_value = placement_question.correct_option,
                        order_index = option_order
                    WHERE id = v_option_id;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;

    FOR candidate_course IN
        SELECT * FROM (VALUES
            ('Basic Vocabulary Builder', 'VOCABULARY', 'A1', 1, 'Build a practical base of everyday nouns, verbs, adjectives, and short phrases.'),
            ('Present Simple Mastery', 'GRAMMAR', 'A1', 2, 'Practice present simple statements, negatives, questions, and common time expressions.'),
            ('Reading Short Conversations', 'READING', 'A1', 3, 'Read short everyday dialogues and identify key details, intent, and sequence.'),
            ('Sentence Structure Basics', 'WRITING', 'A1', 4, 'Write clear subject-verb-object sentences with basic punctuation and capitalization.'),
            ('Everyday Communication', 'COMPREHENSIVE', 'A1', 5, 'Combine vocabulary, grammar, reading, and writing in everyday communication tasks.')
        ) AS c(name, type, level, order_index, description)
        ORDER BY c.order_index
    LOOP
        course_name := 'Phase 8 AI Demo - ' || candidate_course.name;
        topic_name := 'Getting Started';
        lesson_title := 'Start ' || candidate_course.name;
        candidate_skill_type := CASE WHEN candidate_course.type = 'COMPREHENSIVE' THEN 'SPEAKING' ELSE candidate_course.type END;

        UPDATE courses SET
            teacher_id = v_teacher_id,
            description = candidate_course.description,
            type = candidate_course.type,
            level = candidate_course.level,
            order_index = 200 + candidate_course.order_index,
            status = 'APPROVED',
            reviewed_by = v_admin_id,
            reviewed_at = CURRENT_TIMESTAMP,
            published_at = COALESCE(published_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE name = course_name
        RETURNING id INTO v_course_id;

        IF v_course_id IS NULL THEN
            INSERT INTO courses (
                teacher_id, name, description, type, level, order_index, status,
                reviewed_by, reviewed_at, published_at, created_at, updated_at
            )
            VALUES (
                v_teacher_id,
                course_name,
                candidate_course.description,
                candidate_course.type,
                candidate_course.level,
                200 + candidate_course.order_index,
                'APPROVED',
                v_admin_id,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
            RETURNING id INTO v_course_id;
        END IF;

        UPDATE topics SET
            description = 'Introductory topic for the Phase 8 recommendation demo course.',
            order_index = 1
        WHERE topics.course_id = v_course_id AND topics.name = topic_name
        RETURNING id INTO v_topic_id;

        IF v_topic_id IS NULL THEN
            INSERT INTO topics (course_id, name, description, order_index)
            VALUES (v_course_id, topic_name, 'Introductory topic for the Phase 8 recommendation demo course.', 1)
            RETURNING id INTO v_topic_id;
        END IF;

        UPDATE lessons SET
            teacher_id = v_teacher_id,
            description = 'Approved non-placement lesson so this course is eligible for AI recommendations.',
            content = 'This lesson provides a short introduction, examples, and a practice task for ' || candidate_course.name || '.',
            skill_type = candidate_skill_type,
            order_index = 1,
            xp_reward = 20,
            is_locked = FALSE,
            is_placement_test = FALSE,
            status = 'APPROVED',
            reviewed_by = v_admin_id,
            reviewed_at = CURRENT_TIMESTAMP,
            published_at = COALESCE(published_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE lessons.topic_id = v_topic_id AND lessons.title = lesson_title
        RETURNING id INTO v_lesson_id;

        IF v_lesson_id IS NULL THEN
            INSERT INTO lessons (
                topic_id, teacher_id, title, description, content, skill_type, order_index,
                xp_reward, is_locked, is_placement_test, status, reviewed_by, reviewed_at, published_at, created_at, updated_at
            )
            VALUES (
                v_topic_id,
                v_teacher_id,
                lesson_title,
                'Approved non-placement lesson so this course is eligible for AI recommendations.',
                'This lesson provides a short introduction, examples, and a practice task for ' || candidate_course.name || '.',
                candidate_skill_type,
                1,
                20,
                FALSE,
                FALSE,
                'APPROVED',
                v_admin_id,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            );
        END IF;
    END LOOP;

    RAISE NOTICE 'Seeded Phase 8 AI demo users admin %, teacher %, student %', v_admin_id, v_teacher_id, v_student_id;
END $$;

-- Verification SQL:
-- Expected: 4 placement courses plus 5 recommendation candidate courses grouped by type.
-- SELECT type, count(*) FROM courses WHERE name LIKE 'Phase 8 AI Demo%' GROUP BY type;
-- Expected: 5 approved placement questions for each required course type and skill_type.
-- SELECT c.type, l.skill_type, count(*) AS approved_placement_questions
-- FROM questions q
-- JOIN lessons l ON l.id = q.lesson_id
-- JOIN topics t ON t.id = l.topic_id
-- JOIN courses c ON c.id = t.course_id
-- WHERE c.name LIKE 'Phase 8 AI Demo - Placement %'
--   AND c.status = 'APPROVED'
--   AND l.status = 'APPROVED'
--   AND l.is_placement_test = true
--   AND q.status = 'APPROVED'
-- GROUP BY c.type, l.skill_type
-- ORDER BY c.type;
-- Expected: zero rows. Any row returned has an invalid option count or correct-option count.
-- SELECT q.id, q.question_text, count(o.id) AS option_count, count(*) FILTER (WHERE o.is_correct) AS correct_option_count
-- FROM questions q
-- JOIN lessons l ON l.id = q.lesson_id
-- JOIN topics t ON t.id = l.topic_id
-- JOIN courses c ON c.id = t.course_id
-- LEFT JOIN question_options o ON o.question_id = q.id
-- WHERE c.name LIKE 'Phase 8 AI Demo - Placement %'
--   AND l.is_placement_test = true
--   AND q.type = 'MULTIPLE_CHOICE'
-- GROUP BY q.id, q.question_text
-- HAVING count(o.id) <> 4 OR count(*) FILTER (WHERE o.is_correct) <> 1;
-- Expected: exactly the 5 recommendation candidate courses; placement-only courses should not appear here.
-- SELECT c.name, c.type
-- FROM courses c
-- WHERE c.name LIKE 'Phase 8 AI Demo - %'
--   AND c.status = 'APPROVED'
--   AND EXISTS (
--       SELECT 1
--       FROM topics t
--       JOIN lessons l ON l.topic_id = t.id
--       WHERE t.course_id = c.id
--         AND l.status = 'APPROVED'
--         AND l.is_placement_test = false
--   )
-- ORDER BY c.name;
-- Expected: each candidate course has at least one approved non-placement lesson.
-- SELECT c.name, c.type, count(l.id) FILTER (WHERE l.status = 'APPROVED' AND l.is_placement_test = false) AS approved_non_placement_lessons
-- FROM courses c
-- JOIN topics t ON t.course_id = c.id
-- JOIN lessons l ON l.topic_id = t.id
-- WHERE c.name IN (
--     'Phase 8 AI Demo - Basic Vocabulary Builder',
--     'Phase 8 AI Demo - Present Simple Mastery',
--     'Phase 8 AI Demo - Reading Short Conversations',
--     'Phase 8 AI Demo - Sentence Structure Basics',
--     'Phase 8 AI Demo - Everyday Communication'
-- )
-- GROUP BY c.name, c.type
-- ORDER BY c.name;
-- SELECT q.knowledge_point, count(*) FROM questions q JOIN lessons l ON l.id = q.lesson_id WHERE l.is_placement_test = true GROUP BY q.knowledge_point;

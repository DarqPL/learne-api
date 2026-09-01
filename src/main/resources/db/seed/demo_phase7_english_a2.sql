-- Demo data for Phase 7 student learning UI.
-- This seed is intentionally not a Flyway migration. Run manually against a local/dev database.
-- Demo logins use the local admin password hash, so the default password is Admin123.

DO $$
DECLARE
    admin_id BIGINT;
    teacher_id BIGINT;
    student_id BIGINT;
    course_id BIGINT;
    topic_id BIGINT;
    lesson_id BIGINT;
    question_id BIGINT;
    demo_password_hash TEXT := '$2a$10$JKjLIkGQVUOySbEeznvabu1P8/lGVdkLZKZe1.u48yWnj8PpxMAg2';
    topic_names TEXT[] := ARRAY[
        'Meeting People for the First Time',
        'Talking About Daily Routines',
        'Ordering Food and Drinks',
        'Asking for Directions',
        'Shopping and Comparing Prices',
        'Making Plans with Friends',
        'Talking About Past Experiences',
        'Describing Health and Feelings',
        'Workplace Small Talk',
        'Travel Check-in and Simple Requests'
    ];
    lesson_titles TEXT[] := ARRAY[
        'Start a Friendly Conversation',
        'Describe Your Day Clearly',
        'Order at a Cafe with Confidence',
        'Find Your Way Around Town',
        'Ask About Products and Prices',
        'Suggest Plans and Respond Naturally',
        'Tell a Simple Story About the Past',
        'Explain Symptoms and Feelings',
        'Keep a Short Workplace Conversation Going',
        'Handle Basic Travel Situations'
    ];
    skill_types TEXT[] := ARRAY[
        'SPEAKING', 'GRAMMAR', 'SPEAKING', 'LISTENING', 'VOCABULARY',
        'SPEAKING', 'GRAMMAR', 'SPEAKING', 'SPEAKING', 'LISTENING'
    ];
    lesson_content TEXT[] := ARRAY[
        'In this lesson, you learn how to greet someone, introduce yourself, and ask simple follow-up questions. Focus on short, natural sentences: "Hi, I am Minh", "Nice to meet you", and "Where are you from?" A good first conversation is polite, short, and easy to continue.',
        'This lesson helps you describe a normal day using the present simple tense. Use time phrases such as "in the morning", "after work", and "at night". Try to connect actions with simple sequence words: first, then, after that, finally.',
        'This lesson prepares you to order food and drinks in a cafe. Practice polite requests with "Can I have...?", "I would like...", and "Could you make it...?" You will also learn how to ask about size, price, and payment.',
        'This lesson focuses on asking for and understanding simple directions. Useful phrases include "Where is...?", "How do I get to...?", "turn left", "go straight", and "next to". Listen for landmarks and short instruction chunks.',
        'This lesson teaches common shopping language. You will ask about price, size, color, and comparison. Practice phrases like "How much is this?", "Do you have it in blue?", and "This one is cheaper than that one."',
        'This lesson helps you make plans with friends. You will suggest an activity, accept or decline politely, and agree on time and place. Practice "Let us...", "How about...?", "That sounds good", and "Maybe another time."',
        'This lesson introduces simple past storytelling. You will talk about where you went, what you did, and how you felt. Use regular and common irregular verbs: went, saw, bought, had, enjoyed.',
        'This lesson gives you language for health and feelings. You will describe basic symptoms and emotions using "I have...", "I feel...", and "I need..." Keep the message clear so another person can help you.',
        'This lesson covers short workplace small talk. You will greet coworkers, ask about simple tasks, and respond politely. The goal is to sound friendly and professional without using long or complex sentences.',
        'This lesson prepares you for travel check-in and simple requests. You will ask about a booking, request help, and confirm information. Practice clear phrases: "I have a reservation", "Can you help me?", and "Could I have...?"'
    ];
BEGIN
    SELECT id INTO admin_id FROM users WHERE email = 'admin@learne.local' ORDER BY id LIMIT 1;

    IF admin_id IS NULL THEN
        INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
        VALUES ('admin@learne.local', demo_password_hash, 'LearnE Admin', 'ADMIN', TRUE, FALSE, FALSE)
        RETURNING id INTO admin_id;
    ELSE
        SELECT password_hash INTO demo_password_hash FROM users WHERE id = admin_id;
    END IF;

    DELETE FROM courses WHERE name = 'English for Everyday Communication A2';

    INSERT INTO users (email, password_hash, full_name, role, avatar_url, is_active, is_locked, must_change_password)
    VALUES (
        'teacher.demo@learne.local',
        demo_password_hash,
        'Ms. Anna Nguyen',
        'TEACHER',
        'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=240&q=80',
        TRUE,
        FALSE,
        FALSE
    )
    ON CONFLICT (email) DO UPDATE SET
        password_hash = EXCLUDED.password_hash,
        full_name = EXCLUDED.full_name,
        role = EXCLUDED.role,
        avatar_url = EXCLUDED.avatar_url,
        is_active = TRUE,
        is_locked = FALSE,
        must_change_password = FALSE,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO teacher_id;

    INSERT INTO teacher_profiles (user_id, bio, expertise, years_of_experience, avatar_url, introduction_video_url, status)
    VALUES (
        teacher_id,
        'Giang vien tieng Anh giao tiep voi kinh nghiem huong dan hoc vien nguoi Viet tu mat goc den trinh do A2-B1. Bai hoc tap trung vao tinh huong doi thuong, mau cau ngan gon va phan hoi de ap dung ngay.',
        'English Communication, A2 Grammar, Speaking Practice',
        8,
        'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=240&q=80',
        'https://example.com/demo-teacher-introduction.mp4',
        'ACTIVE'
    )
    ON CONFLICT (user_id) DO UPDATE SET
        bio = EXCLUDED.bio,
        expertise = EXCLUDED.expertise,
        years_of_experience = EXCLUDED.years_of_experience,
        avatar_url = EXCLUDED.avatar_url,
        introduction_video_url = EXCLUDED.introduction_video_url,
        status = 'ACTIVE',
        updated_at = CURRENT_TIMESTAMP;

    INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
    VALUES ('student.demo@learne.local', demo_password_hash, 'Tran Minh Demo', 'STUDENT', TRUE, FALSE, FALSE)
    ON CONFLICT (email) DO UPDATE SET
        password_hash = EXCLUDED.password_hash,
        full_name = EXCLUDED.full_name,
        role = EXCLUDED.role,
        is_active = TRUE,
        is_locked = FALSE,
        must_change_password = FALSE,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO student_id;

    INSERT INTO courses (
        teacher_id, name, description, type, level, order_index, status,
        reviewed_by, reviewed_at, published_at, created_at, updated_at
    )
    VALUES (
        teacher_id,
        'English for Everyday Communication A2',
        'Khoa hoc giao tiep tieng Anh A2 gom 10 tinh huong doi thuong: lam quen, thoi quen hang ngay, goi mon, hoi duong, mua sam, hen gap ban be, ke chuyen qua khu, suc khoe, noi chuyen cong so va du lich. Moi bai hoc co noi dung ngan gon, vi du thuc te va quiz 10 cau de kiem tra ngay sau khi hoc.',
        'COMPREHENSIVE',
        'A2',
        10,
        'APPROVED',
        admin_id,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    )
    RETURNING id INTO course_id;

    FOR i IN 1..10 LOOP
        INSERT INTO topics (course_id, name, description, order_index)
        VALUES (
            course_id,
            topic_names[i],
            'Chu de ' || i || ' giup hoc vien luyen mot tinh huong giao tiep A2 cu the voi tu vung, mau cau va bai tap ngan sau bai hoc.',
            i
        )
        RETURNING id INTO topic_id;

        INSERT INTO lessons (
            topic_id, teacher_id, title, description, content, skill_type, order_index,
            xp_reward, is_locked, is_placement_test, status, reviewed_by, reviewed_at, published_at, created_at, updated_at
        )
        VALUES (
            topic_id,
            teacher_id,
            lesson_titles[i],
            'Bai hoc ' || i || ' trong khoa English for Everyday Communication A2.',
            lesson_content[i] || E'\n\nKey phrases:\n- Can you help me with this?\n- I would like to practice this sentence.\n- Could you say that again, please?\n\nMini task: Write two short sentences that you can use in this situation.',
            skill_types[i],
            1,
            25,
            FALSE,
            FALSE,
            'APPROVED',
            admin_id,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP
        )
        RETURNING id INTO lesson_id;

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'MULTIPLE_CHOICE', 'Choose the most natural greeting for this lesson situation.', 'A short polite greeting is best for starting an A2 conversation.', NULL, 'greetings', 1, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO question_id;
        INSERT INTO question_options (question_id, option_text, is_correct, order_index) VALUES
            (question_id, 'Nice to meet you.', TRUE, 1),
            (question_id, 'I am yesterday.', FALSE, 2),
            (question_id, 'Blue coffee quickly.', FALSE, 3),
            (question_id, 'She go to market.', FALSE, 4);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'TRUE_FALSE', 'The sentence "Could you say that again, please?" is polite.', 'This phrase is a polite request for repetition.', NULL, 'polite requests', 2, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO question_id;
        INSERT INTO question_options (question_id, option_text, is_correct, order_index) VALUES
            (question_id, 'Đúng', TRUE, 1),
            (question_id, 'Sai', FALSE, 2);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'FILL_BLANK', 'Fill in the blank: I ____ like a cup of tea.', 'Use "would like" for a polite request.', 'would', 'would like', 3, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'WORD_ORDER', 'Put the words in order: please / help / you / Can / me', 'The correct order forms a polite request.', 'Can you help me please', 'word order', 4, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'TRANSLATION', 'Translate into English: Bạn có thể giúp tôi không?', 'This is a common polite request.', 'Can you help me', 'translation', 5, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'SHORT_ANSWER', 'Write a short answer to: How are you today?', 'A clear short answer is enough at A2 level.', 'I am fine thank you', 'short response', 6, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'MULTIPLE_CHOICE', 'Which sentence is grammatically correct?', 'The present simple question uses "do" with "you".', NULL, 'basic grammar', 7, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO question_id;
        INSERT INTO question_options (question_id, option_text, is_correct, order_index) VALUES
            (question_id, 'Do you speak English?', TRUE, 1),
            (question_id, 'Does you speaks English?', FALSE, 2),
            (question_id, 'You speaking English?', FALSE, 3),
            (question_id, 'Are you speak English?', FALSE, 4);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'TRUE_FALSE', 'The phrase "I would like" is more polite than "I want" in many service situations.', '"I would like" is softer and more polite.', NULL, 'politeness', 8, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO question_id;
        INSERT INTO question_options (question_id, option_text, is_correct, order_index) VALUES
            (question_id, 'Đúng', TRUE, 1),
            (question_id, 'Sai', FALSE, 2);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'FILL_BLANK', 'Fill in the blank: Could you ____ that again?', 'Use "say" in the request "say that again".', 'say', 'listening repair', 9, 'A2', 5, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

        INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
        VALUES (lesson_id, teacher_id, 'PRONUNCIATION', 'Read the target sentence aloud.', 'Phase 7 displays target text only. Audio scoring belongs to Phase 9.', 'Could you say that again, please?', 'pronunciation target', 10, 'A2', 0, 'APPROVED', admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
    END LOOP;

    RAISE NOTICE 'Seeded demo course %, teacher %, student %', course_id, teacher_id, student_id;
END $$;

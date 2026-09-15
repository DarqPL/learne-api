import argparse
import hashlib
import json
from pathlib import Path


DEFAULT_MANIFEST = Path("database/realistic-content/listening-manifest.json")
DEFAULT_OUTPUT = Path("database/seed-realistic-full.sql")

LEVELS = ("A1", "A2", "B1")
SKILL_COURSES = (
    ("GRAMMAR", "Grammar Foundations"),
    ("VOCABULARY", "Vocabulary Builder"),
    ("READING", "Reading Skills"),
    ("WRITING", "Writing Workshop"),
    ("LISTENING", "Listening Everyday English"),
    ("SPEAKING", "Speaking Practice"),
)
LISTENING_LABELS = {
    "A1": "Listening Everyday English",
    "A2": "Listening in Real Situations",
    "B1": "Listening for Details",
}
PLACEMENT_SKILLS = ("VOCABULARY", "GRAMMAR", "READING", "WRITING")
COMPREHENSIVE_TOPIC_SKILLS = (
    "GRAMMAR",
    "VOCABULARY",
    "READING",
    "WRITING",
    "LISTENING",
    "SPEAKING",
    "GRAMMAR",
    "VOCABULARY",
    "READING",
)
TEACHERS = (
    ("nguyen.minh.ha@learne.local", "Nguyen Minh Ha"),
    ("tran.quoc.anh@learne.local", "Tran Quoc Anh"),
    ("pham.linh.chi@learne.local", "Pham Linh Chi"),
    ("le.hoang.nam@learne.local", "Le Hoang Nam"),
    ("doan.thu.trang@learne.local", "Doan Thu Trang"),
)
STUDENT_NAMES = (
    "Nguyen Minh An",
    "Tran Gia Binh",
    "Pham Linh Chi",
    "Le Quang Dung",
    "Do Thanh Em",
    "Vo Huong Giang",
    "Bui Duc Hai",
    "Dang Mai Hoa",
    "Hoang Bao Khanh",
    "Ngo Ngoc Lan",
    "Duong Thuy Linh",
    "Phan Hoang Long",
    "Vu Ngoc Mai",
    "Truong Duc Minh",
    "Ly Hai Nam",
    "Huynh Bao Ngan",
    "Cao Minh Phong",
    "Dinh Tan Phuc",
    "Mai Anh Quan",
    "Ta Nhu Quynh",
    "Ha Thanh Son",
    "Phung Gia Tam",
    "Lam Minh Thao",
    "Nguyen Duc Thien",
    "Tran Thu Trang",
    "Pham Bao Truc",
    "Le Anh Tuan",
    "Do Thanh Uyen",
    "Vo Quoc Viet",
    "Bui Hai Yen",
)
QUIZ_BANKS = {
    "GRAMMAR": (
        {
            "prompt": "Select the sentence with the correct present simple form.",
            "correct": "She studies English after dinner.",
            "options": ("She studies English after dinner.", "She study English after dinner.", "She studying English after dinner.", "She studied English every dinner."),
            "explanation": "Use the -s form with he, she, or it in present simple statements.",
            "point": "Present simple third-person verbs",
        },
        {
            "prompt": "Select the sentence that uses a countable noun correctly.",
            "correct": "There are three books on the desk.",
            "options": ("There are three books on the desk.", "There is three books on the desk.", "There are three book on the desk.", "There be three books on the desk."),
            "explanation": "Use are with plural countable nouns such as books.",
            "point": "Plural countable nouns",
        },
        {
            "prompt": "Select the question with correct word order.",
            "correct": "Where does your brother work?",
            "options": ("Where does your brother work?", "Where your brother does work?", "Where does work your brother?", "Where your brother work does?"),
            "explanation": "Wh-questions in present simple use question word, auxiliary, subject, and base verb.",
            "point": "Present simple question order",
        },
        {
            "prompt": "Select the sentence that uses the past tense correctly.",
            "correct": "We visited the museum yesterday.",
            "options": ("We visited the museum yesterday.", "We visit the museum yesterday.", "We visiting the museum yesterday.", "We visits the museum yesterday."),
            "explanation": "Use a past-tense verb with finished past time expressions such as yesterday.",
            "point": "Regular past tense verbs",
        },
    ),
    "VOCABULARY": (
        {
            "prompt": "Select the word that best completes the sentence: The soup is too hot, so please wait before you eat it.",
            "correct": "carefully",
            "options": ("carefully", "loudly", "rarely", "early"),
            "explanation": "Carefully describes doing something with attention to safety or detail.",
            "point": "Adverbs for safe actions",
        },
        {
            "prompt": "Select the word that means a person who buys something in a shop.",
            "correct": "customer",
            "options": ("customer", "driver", "neighbor", "manager"),
            "explanation": "A customer is someone who buys goods or services.",
            "point": "Shopping vocabulary",
        },
        {
            "prompt": "Select the phrase that is most appropriate when asking for help politely.",
            "correct": "Could you help me, please?",
            "options": ("Could you help me, please?", "You must help me now.", "Help me because I say so.", "Why are you not helping?"),
            "explanation": "Could you and please make the request polite.",
            "point": "Polite requests",
        },
        {
            "prompt": "Select the word that best matches the meaning: very tired after a long day.",
            "correct": "exhausted",
            "options": ("exhausted", "curious", "generous", "confident"),
            "explanation": "Exhausted means extremely tired.",
            "point": "Feelings adjectives",
        },
    ),
    "READING": (
        {
            "prompt": "Read the notice: The library closes at six on Fridays. What does the notice tell readers?",
            "correct": "The library has an earlier Friday closing time.",
            "options": ("The library has an earlier Friday closing time.", "The library opens later on Fridays.", "The library is closed all Friday.", "The library changes every weekday schedule."),
            "explanation": "The notice gives a closing time for Fridays.",
            "point": "Reading notices for key details",
        },
        {
            "prompt": "Read the message: I missed the bus, so I will arrive ten minutes late. Why will the speaker be late?",
            "correct": "The speaker missed the bus.",
            "options": ("The speaker missed the bus.", "The speaker forgot the address.", "The speaker lost a ticket.", "The speaker changed the meeting place."),
            "explanation": "The reason appears before so in the message.",
            "point": "Cause and result in short texts",
        },
        {
            "prompt": "Read the sentence: Maria packed an umbrella because the sky was dark. What can readers infer?",
            "correct": "Maria thought it might rain.",
            "options": ("Maria thought it might rain.", "Maria wanted to buy an umbrella.", "Maria was going to the beach.", "Maria left the umbrella at home."),
            "explanation": "A dark sky often suggests possible rain.",
            "point": "Simple inference from context",
        },
        {
            "prompt": "Read the advertisement: Fresh bread is available every morning before nine. When can shoppers buy fresh bread?",
            "correct": "In the morning before nine.",
            "options": ("In the morning before nine.", "Only after lunch.", "Late in the evening.", "At midnight."),
            "explanation": "The time phrase before nine gives the availability window.",
            "point": "Time information in advertisements",
        },
    ),
    "WRITING": (
        {
            "prompt": "Select the clearest opening sentence for a friendly email about weekend plans.",
            "correct": "I hope you are well. Would you like to meet on Saturday?",
            "options": ("I hope you are well. Would you like to meet on Saturday?", "Saturday meet you well hope.", "I weekend Saturday because plans.", "Meet Saturday yes no maybe."),
            "explanation": "A friendly email can open with a greeting and a clear invitation.",
            "point": "Friendly email openings",
        },
        {
            "prompt": "Select the sentence with the clearest punctuation.",
            "correct": "After class, I usually review my notes.",
            "options": ("After class, I usually review my notes.", "After class I usually, review my notes.", "After, class I usually review my notes.", "After class I usually review, my notes."),
            "explanation": "Use a comma after a short introductory phrase.",
            "point": "Commas after introductory phrases",
        },
        {
            "prompt": "Select the best sentence to describe a daily routine in a short paragraph.",
            "correct": "I take the bus to school every morning.",
            "options": ("I take the bus to school every morning.", "Bus school morning take every I.", "I took tomorrow bus school.", "Every bus is a school morning."),
            "explanation": "The sentence has a clear subject, verb, object, and time phrase.",
            "point": "Routine sentence structure",
        },
        {
            "prompt": "Select the sentence that links two ideas smoothly.",
            "correct": "I was tired, but I finished my homework.",
            "options": ("I was tired, but I finished my homework.", "I was tired my homework finished but.", "But I was tired, homework.", "I finished tired but was homework."),
            "explanation": "But connects contrasting ideas in a natural order.",
            "point": "Linking contrast with but",
        },
    ),
    "LISTENING": (
        {
            "prompt": "In a short conversation, a speaker says the train leaves at half past eight. Select the correct departure time.",
            "correct": "8:30",
            "options": ("8:30", "8:15", "7:30", "9:00"),
            "explanation": "Half past eight means thirty minutes after eight.",
            "point": "Listening for times",
        },
        {
            "prompt": "A customer asks for a table for two near the window. What does the customer want?",
            "correct": "A table for two people near the window.",
            "options": ("A table for two people near the window.", "Two windows near the kitchen.", "A takeaway order for two meals.", "A table far from the window."),
            "explanation": "The request includes both the number of people and the preferred location.",
            "point": "Listening for restaurant requests",
        },
        {
            "prompt": "A colleague says the meeting has moved from Monday to Wednesday. Select the new meeting day.",
            "correct": "Wednesday",
            "options": ("Wednesday", "Monday", "Tuesday", "Friday"),
            "explanation": "Moved from Monday to Wednesday identifies Wednesday as the new day.",
            "point": "Listening for schedule changes",
        },
        {
            "prompt": "A friend says she feels better after resting. What helped her feel better?",
            "correct": "Resting",
            "options": ("Resting", "Running quickly", "Missing lunch", "Leaving early"),
            "explanation": "The phrase after resting shows what improved how she felt.",
            "point": "Listening for reasons",
        },
    ),
    "SPEAKING": (
        {
            "prompt": "Select the most natural response to: How was your weekend?",
            "correct": "It was relaxing, thanks. I visited my family.",
            "options": ("It was relaxing, thanks. I visited my family.", "Weekend was family relaxing visited thanks I.", "Yes, because weekend maybe.", "I am weekend yesterday family."),
            "explanation": "The response answers the question and adds a short supporting detail.",
            "point": "Answering small-talk questions",
        },
        {
            "prompt": "Select the best way to ask someone to repeat information politely.",
            "correct": "Could you say that again, please?",
            "options": ("Could you say that again, please?", "Repeat now.", "You say again because I missed.", "Why did you talk once?"),
            "explanation": "Could you and please make the request polite and natural.",
            "point": "Polite clarification requests",
        },
        {
            "prompt": "Select the most natural response when agreeing to a plan.",
            "correct": "That sounds good. See you at three.",
            "options": ("That sounds good. See you at three.", "Plan three sound see good that.", "No yes three perhaps.", "I disagree and will arrive at three."),
            "explanation": "The response accepts the plan and confirms the time.",
            "point": "Agreeing and confirming plans",
        },
        {
            "prompt": "Select the best sentence for describing a picture in a speaking task.",
            "correct": "Two people are talking in a busy cafe.",
            "options": ("Two people are talking in a busy cafe.", "Cafe busy talking people two are.", "Two people talked tomorrow in a cafe.", "A cafe talks to two busy people."),
            "explanation": "The sentence clearly describes who is in the picture and what they are doing.",
            "point": "Describing pictures clearly",
        },
    ),
}


def load_manifest(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    items = data.get("items")
    if not isinstance(items, list):
        raise ValueError("listening manifest items must be a list")
    for index, item in enumerate(items):
        for key in ("id", "courseName", "topicName", "lessonTitle", "transcript"):
            if not item.get(key):
                raise ValueError(f"manifest item {index} missing {key}")
    return data


def _options(template):
    return list(template["options"])


def _question(skill, course_level, order_index, context):
    template = QUIZ_BANKS[skill][(order_index - 1) % len(QUIZ_BANKS[skill])]
    correct = template["correct"]
    return {
        "type": "MULTIPLE_CHOICE",
        "skill": skill,
        "questionText": f"{context}: {template['prompt']}",
        "explanation": template["explanation"],
        "correctAnswer": correct,
        "knowledgePoint": f"{course_level} {template['point']}",
        "orderIndex": order_index,
        "difficulty": course_level,
        "xpReward": 5,
        "options": _options(template),
    }


def _lesson(skill, level, topic_index, lesson_index, title=None, audio=None, placement=False):
    question_count = 60 if placement else 10
    lesson_title = title or f"{level} {skill.title()} Lesson {topic_index}.{lesson_index}"
    return {
        "title": lesson_title,
        "description": f"{level} {skill.lower()} practice lesson for independent learners.",
        "content": f"Guided practice material for {lesson_title}.",
        "skillType": skill,
        "audioUrl": (audio or {}).get("audioUrl", ""),
        "audioPublicId": (audio or {}).get("audioPublicId", ""),
        "orderIndex": lesson_index,
        "xpReward": 10 if not placement else 0,
        "isPlacementTest": placement,
        "questions": [
            _question(skill, level, i, lesson_title)
            for i in range(1, question_count + 1)
        ],
    }


def _normalized_audio_item(item):
    item = dict(item)
    audio_public_id = item.get("audioPublicId") or f"learne/listening-audio/{item['id']}"
    item["audioPublicId"] = audio_public_id
    item["audioUrl"] = (
        item.get("audioUrl")
        or f"https://cdn.learne.local/audio/{item.get('fileName') or item['id'] + '.mp3'}"
    )
    return item


def _listening_items_for_course(manifest_items, course_name, level):
    selected = [
        _normalized_audio_item(item)
        for item in manifest_items
        if item.get("courseName") == course_name and item.get("courseLevel") == level
    ]
    unique_audio = {
        (item["audioPublicId"], item["audioUrl"])
        for item in selected
    }
    if len(selected) != 32 or len(unique_audio) != 32:
        raise ValueError(
            f"{course_name} requires 32 unique listening manifest items; "
            f"found {len(unique_audio)} unique audio items from {len(selected)} manifest items"
        )
    return selected


def _build_standard_course(skill, level, label, teacher_index, order_index, manifest_items):
    course_name = f"{level} {label}"
    topics = []
    if skill == "LISTENING":
        grouped = {}
        for fallback_index, item in enumerate(
            _listening_items_for_course(manifest_items, course_name, level), start=1
        ):
            item["topicName"] = item.get("topicName") or f"{level} Listening Topic {((fallback_index - 1) // 4) + 1}"
            grouped.setdefault(item["topicName"], []).append(item)
        for topic_index, (topic_name, items) in enumerate(grouped.items(), start=1):
            topics.append(
                {
                    "name": topic_name,
                    "description": f"{level} listening scenarios for {topic_name.lower()}.",
                    "orderIndex": topic_index,
                    "lessons": [
                        _lesson(
                            skill,
                            level,
                            topic_index,
                            lesson_index,
                            title=item["lessonTitle"],
                            audio=item,
                        )
                        for lesson_index, item in enumerate(items, start=1)
                    ],
                }
            )
    else:
        for topic_index in range(1, 8):
            topics.append(
                {
                    "name": f"{level} {skill.title()} Topic {topic_index}",
                    "description": f"{level} {skill.lower()} topic {topic_index}.",
                    "orderIndex": topic_index,
                    "lessons": [
                        _lesson(skill, level, topic_index, lesson_index)
                        for lesson_index in range(1, 4)
                    ],
                }
            )
        if level == "A1" and skill in PLACEMENT_SKILLS:
            topics[0]["lessons"].append(
                _lesson(
                    skill,
                    level,
                    1,
                    99,
                    title=f"Placement {skill.title()} Bank",
                    placement=True,
                )
            )

    return {
        "name": course_name,
        "description": f"Teacher-led {level} {skill.lower()} course.",
        "type": skill,
        "level": level,
        "orderIndex": order_index,
        "teacherEmail": TEACHERS[teacher_index - 1][0],
        "topics": topics,
    }


def _build_comprehensive_course(level, variant, teacher_index, order_index):
    topics = []
    for topic_index, skill in enumerate(COMPREHENSIVE_TOPIC_SKILLS, start=1):
        topics.append(
            {
                "name": f"{level} Comprehensive {variant} {skill.title()}",
                "description": f"Integrated {skill.lower()} practice for comprehensive course {variant}.",
                "orderIndex": topic_index,
                "lessons": [
                    _lesson(skill, level, topic_index, lesson_index)
                    for lesson_index in range(1, 5)
                ],
            }
        )
    return {
        "name": f"{level} Comprehensive Path {variant}",
        "description": f"Teacher-led {level} comprehensive pathway {variant}.",
        "type": "COMPREHENSIVE",
        "level": level,
        "orderIndex": order_index,
        "teacherEmail": TEACHERS[teacher_index - 1][0],
        "topics": topics,
    }


def _build_course_recommendation_paths(students, courses):
    eligible_courses = [
        course
        for course in courses
        if not any(
            lesson["isPlacementTest"]
            for topic in course["topics"]
            for lesson in topic["lessons"]
        )
    ]
    paths = []
    for student_index, student in enumerate(students):
        selected_courses = [
            eligible_courses[(student_index + offset) % len(eligible_courses)]
            for offset in range(4)
        ]
        paths.append(
            {
                "studentEmail": student["email"],
                "status": "ACTIVE",
                "source": "AI_SERVICE",
                "weaknessesJson": json.dumps(
                    {
                        "skills": ["GRAMMAR", "VOCABULARY", "READING", "WRITING"],
                        "placementScoreBand": "developing",
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                "items": [
                    {
                        "courseName": course["name"],
                        "sequenceOrder": sequence_order,
                        "reason": f"Recommended {course['level']} {course['type'].lower()} practice based on placement results.",
                        "score": f"{0.95 - (sequence_order - 1) * 0.05:.4f}",
                    }
                    for sequence_order, course in enumerate(selected_courses, start=1)
                ],
            }
        )
    return paths


def build_seed(manifest):
    teachers = [
        {
            "email": email,
            "fullName": full_name,
            "bio": f"English teacher specializing in practical classroom activities for {full_name}.",
            "expertise": "Grammar, vocabulary, reading, writing, listening, speaking",
            "years": 5 + index,
        }
        for index, (email, full_name) in enumerate(TEACHERS, start=1)
    ]
    students = [
        {
            "email": f"student{i:02d}@learne.local",
            "fullName": STUDENT_NAMES[i - 1],
        }
        for i in range(1, 31)
    ]
    courses = []
    order_index = 1
    for skill, label in SKILL_COURSES:
        for level_index, level in enumerate(LEVELS, start=1):
            course_label = LISTENING_LABELS[level] if skill == "LISTENING" else label
            courses.append(
                _build_standard_course(
                    skill,
                    level,
                    course_label,
                    ((order_index - 1) % 5) + 1,
                    order_index,
                    manifest["items"],
                )
            )
            order_index += 1
    for level in LEVELS:
        for variant in (1, 2):
            courses.append(
                _build_comprehensive_course(
                    level, variant, ((order_index - 1) % 5) + 1, order_index
                )
            )
            order_index += 1
    course_recommendation_paths = _build_course_recommendation_paths(students, courses)
    return {
        "admin": {
            "email": "admin@learne.local",
            "fullName": "Learne Admin",
        },
        "teachers": teachers,
        "students": students,
        "courses": courses,
        "courseRecommendationPaths": course_recommendation_paths,
    }


def _sql_literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def _json_literal(value):
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return _dollar_quote(encoded, "learne_realistic_seed_json") + "::jsonb"


def _dollar_quote(value, prefix):
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]
    for suffix in range(100):
        delimiter = f"${prefix}_{digest}_{suffix}$"
        if delimiter not in value:
            return f"{delimiter}{value}{delimiter}"
    raise ValueError(f"could not find safe dollar quote delimiter for {prefix}")


def _counts(seed):
    lessons = [
        lesson
        for course in seed["courses"]
        for topic in course["topics"]
        for lesson in topic["lessons"]
    ]
    questions = [question for lesson in lessons for question in lesson["questions"]]
    return len(seed["courses"]), len(lessons), len(questions)


def render_sql(seed):
    course_count, lesson_count, question_count = _counts(seed)
    body = f"""DECLARE
    v_admin_id BIGINT;
    v_teacher_id BIGINT;
    v_student_id BIGINT;
    v_course_id BIGINT;
    v_topic_id BIGINT;
    v_lesson_id BIGINT;
    v_question_id BIGINT;
    v_option_id BIGINT;
    v_enrollment_id BIGINT;
    v_attempt_id BIGINT;
    v_recommendation_path_id BIGINT;
    v_check_id BIGINT;
    v_conversation_id BIGINT;
    v_message_id BIGINT;
    v_default_password_hash TEXT;
    teacher JSONB;
    student JSONB;
    course JSONB;
    topic JSONB;
    lesson JSONB;
    question JSONB;
    option_text TEXT;
    recommendation_path JSONB;
    recommendation_item JSONB;
    student_index INT;
    option_index INT;
    answer_question RECORD;
    v_teachers JSONB := {_json_literal(seed['teachers'])};
    v_students JSONB := {_json_literal(seed['students'])};
    v_courses JSONB := {_json_literal(seed['courses'])};
    v_recommendation_paths JSONB := {_json_literal(seed['courseRecommendationPaths'])};
BEGIN
    SELECT password_hash INTO v_default_password_hash
    FROM users
    WHERE email = 'admin@learne.local'
    ORDER BY id
    LIMIT 1;

    IF v_default_password_hash IS NULL THEN
        v_default_password_hash := '$2a$10$JKjLIkGQVUOySbEeznvabu1P8/lGVdkLZKZe1.u48yWnj8PpxMAg2';
    END IF;

    IF to_regclass('public.ai_feedback') IS NOT NULL THEN
        DELETE FROM ai_feedback;
    END IF;
    IF to_regclass('public.ai_messages') IS NOT NULL THEN
        DELETE FROM ai_messages;
    END IF;
    IF to_regclass('public.ai_conversations') IS NOT NULL THEN
        DELETE FROM ai_conversations;
    END IF;
    IF to_regclass('public.grammar_check_errors') IS NOT NULL THEN
        DELETE FROM grammar_check_errors;
    END IF;
    IF to_regclass('public.grammar_checks') IS NOT NULL THEN
        DELETE FROM grammar_checks;
    END IF;
    DELETE FROM placement_attempt_answers;
    DELETE FROM placement_attempt_questions;
    DELETE FROM placement_attempts;
    DELETE FROM placement_preferences;
    DELETE FROM course_recommendation_items;
    DELETE FROM course_recommendation_paths;
    DELETE FROM learning_path_items;
    DELETE FROM learning_paths;
    DELETE FROM learning_activities;
    DELETE FROM question_answers;
    DELETE FROM quiz_attempts;
    DELETE FROM lesson_progress;
    DELETE FROM course_enrollments;
    DELETE FROM user_stats;
    DELETE FROM question_options;
    DELETE FROM questions;
    DELETE FROM lessons;
    DELETE FROM topics;
    DELETE FROM courses;
    DELETE FROM refresh_tokens rt USING users u WHERE rt.user_id = u.id AND u.role <> 'ADMIN';
    DELETE FROM password_reset_tokens prt USING users u WHERE prt.user_id = u.id AND u.role <> 'ADMIN';
    DELETE FROM users WHERE role <> 'ADMIN';

    INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
    VALUES ({_sql_literal(seed['admin']['email'])}, v_default_password_hash, {_sql_literal(seed['admin']['fullName'])}, 'ADMIN', TRUE, FALSE, FALSE)
    ON CONFLICT (email) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        role = EXCLUDED.role,
        is_active = TRUE,
        is_locked = FALSE,
        must_change_password = FALSE,
        updated_at = CURRENT_TIMESTAMP
    RETURNING id INTO v_admin_id;

    FOR teacher IN SELECT * FROM jsonb_array_elements(v_teachers) LOOP
        INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
        VALUES (teacher->>'email', v_default_password_hash, teacher->>'fullName', 'TEACHER', TRUE, FALSE, FALSE)
        ON CONFLICT (email) DO UPDATE SET
            full_name = EXCLUDED.full_name,
            role = EXCLUDED.role,
            is_active = TRUE,
            is_locked = FALSE,
            must_change_password = FALSE,
            updated_at = CURRENT_TIMESTAMP
        RETURNING id INTO v_teacher_id;

        INSERT INTO teacher_profiles (user_id, bio, expertise, years_of_experience, status)
        VALUES (v_teacher_id, teacher->>'bio', teacher->>'expertise', (teacher->>'years')::INT, 'ACTIVE')
        ON CONFLICT (user_id) DO UPDATE SET
            bio = EXCLUDED.bio,
            expertise = EXCLUDED.expertise,
            years_of_experience = EXCLUDED.years_of_experience,
            status = 'ACTIVE',
            updated_at = CURRENT_TIMESTAMP;
    END LOOP;

    FOR student IN SELECT * FROM jsonb_array_elements(v_students) LOOP
        INSERT INTO users (email, password_hash, full_name, role, is_active, is_locked, must_change_password)
        VALUES (student->>'email', v_default_password_hash, student->>'fullName', 'STUDENT', TRUE, FALSE, FALSE)
        ON CONFLICT (email) DO UPDATE SET
            full_name = EXCLUDED.full_name,
            role = EXCLUDED.role,
            is_active = TRUE,
            is_locked = FALSE,
            must_change_password = FALSE,
            updated_at = CURRENT_TIMESTAMP;
    END LOOP;

    FOR course IN SELECT * FROM jsonb_array_elements(v_courses) LOOP
        SELECT u.id INTO v_teacher_id FROM users u WHERE u.email = course->>'teacherEmail';

        UPDATE courses c SET
            teacher_id = v_teacher_id,
            description = course->>'description',
            type = course->>'type',
            level = course->>'level',
            order_index = (course->>'orderIndex')::INT,
            status = 'APPROVED',
            rejection_reason = NULL,
            reviewed_by = v_admin_id,
            reviewed_at = CURRENT_TIMESTAMP,
            published_at = COALESCE(c.published_at, CURRENT_TIMESTAMP),
            updated_at = CURRENT_TIMESTAMP
        WHERE c.name = course->>'name'
        RETURNING c.id INTO v_course_id;

        IF v_course_id IS NULL THEN
            INSERT INTO courses (teacher_id, name, description, type, level, order_index, status, reviewed_by, reviewed_at, published_at, created_at, updated_at)
            VALUES (v_teacher_id, course->>'name', course->>'description', course->>'type', course->>'level', (course->>'orderIndex')::INT, 'APPROVED', v_admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id INTO v_course_id;
        END IF;

        FOR topic IN SELECT * FROM jsonb_array_elements(course->'topics') LOOP
            UPDATE topics t SET
                description = topic->>'description',
                order_index = (topic->>'orderIndex')::INT
            WHERE t.course_id = v_course_id AND t.name = topic->>'name'
            RETURNING t.id INTO v_topic_id;

            IF v_topic_id IS NULL THEN
                INSERT INTO topics (course_id, name, description, order_index)
                VALUES (v_course_id, topic->>'name', topic->>'description', (topic->>'orderIndex')::INT)
                RETURNING id INTO v_topic_id;
            END IF;

            FOR lesson IN SELECT * FROM jsonb_array_elements(topic->'lessons') LOOP
                UPDATE lessons l SET
                    teacher_id = v_teacher_id,
                    description = lesson->>'description',
                    content = lesson->>'content',
                    skill_type = lesson->>'skillType',
                    audio_url = NULLIF(lesson->>'audioUrl', ''),
                    audio_public_id = NULLIF(lesson->>'audioPublicId', ''),
                    order_index = (lesson->>'orderIndex')::INT,
                    xp_reward = (lesson->>'xpReward')::INT,
                    is_locked = FALSE,
                    is_placement_test = (lesson->>'isPlacementTest')::BOOLEAN,
                    status = 'APPROVED',
                    rejection_reason = NULL,
                    reviewed_by = v_admin_id,
                    reviewed_at = CURRENT_TIMESTAMP,
                    published_at = COALESCE(l.published_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                WHERE l.topic_id = v_topic_id AND l.title = lesson->>'title'
                RETURNING l.id INTO v_lesson_id;

                IF v_lesson_id IS NULL THEN
                    INSERT INTO lessons (topic_id, teacher_id, title, description, content, skill_type, audio_url, audio_public_id, order_index, xp_reward, is_locked, is_placement_test, status, reviewed_by, reviewed_at, published_at, created_at, updated_at)
                    VALUES (v_topic_id, v_teacher_id, lesson->>'title', lesson->>'description', lesson->>'content', lesson->>'skillType', NULLIF(lesson->>'audioUrl', ''), NULLIF(lesson->>'audioPublicId', ''), (lesson->>'orderIndex')::INT, (lesson->>'xpReward')::INT, FALSE, (lesson->>'isPlacementTest')::BOOLEAN, 'APPROVED', v_admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    RETURNING id INTO v_lesson_id;
                END IF;

                DELETE FROM question_options qo USING questions q WHERE qo.question_id = q.id AND q.lesson_id = v_lesson_id;
                DELETE FROM questions q WHERE q.lesson_id = v_lesson_id;

                FOR question IN SELECT * FROM jsonb_array_elements(lesson->'questions') LOOP
                    INSERT INTO questions (lesson_id, teacher_id, type, question_text, explanation, correct_answer, knowledge_point, order_index, difficulty, xp_reward, status, reviewed_by, reviewed_at, created_at, updated_at)
                    VALUES (v_lesson_id, v_teacher_id, question->>'type', question->>'questionText', question->>'explanation', question->>'correctAnswer', question->>'knowledgePoint', (question->>'orderIndex')::INT, question->>'difficulty', (question->>'xpReward')::INT, 'APPROVED', v_admin_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    RETURNING id INTO v_question_id;

                    option_index := 1;
                    FOR option_text IN SELECT * FROM jsonb_array_elements_text(question->'options') LOOP
                        INSERT INTO question_options (question_id, option_text, is_correct, order_index)
                        VALUES (v_question_id, option_text, option_text = question->>'correctAnswer', option_index)
                        RETURNING id INTO v_option_id;
                        option_index := option_index + 1;
                    END LOOP;
                END LOOP;
            END LOOP;
        END LOOP;
    END LOOP;

    student_index := 0;
    FOR student IN SELECT * FROM jsonb_array_elements(v_students) LOOP
        student_index := student_index + 1;
        SELECT u.id INTO v_student_id FROM users u WHERE u.email = student->>'email';

        INSERT INTO user_stats (user_id, total_xp, current_streak_days, longest_streak_days, last_activity_date)
        VALUES (v_student_id, 500 + student_index * 15, student_index % 7, 10 + student_index % 12, CURRENT_DATE - (student_index % 5))
        ON CONFLICT (user_id) DO UPDATE SET
            total_xp = EXCLUDED.total_xp,
            current_streak_days = EXCLUDED.current_streak_days,
            longest_streak_days = EXCLUDED.longest_streak_days,
            last_activity_date = EXCLUDED.last_activity_date,
            updated_at = CURRENT_TIMESTAMP;

        FOR course IN SELECT * FROM jsonb_array_elements(v_courses) LIMIT 4 LOOP
            SELECT c.id INTO v_course_id FROM courses c WHERE c.name = course->>'name';
            INSERT INTO course_enrollments (user_id, course_id, status, progress_percent, enrolled_at, completed_at, created_at, updated_at)
            VALUES (v_student_id, v_course_id, CASE WHEN student_index % 3 = 0 THEN 'COMPLETED' ELSE 'ACTIVE' END, 25 + (student_index % 70), CURRENT_TIMESTAMP - INTERVAL '20 days', CASE WHEN student_index % 3 = 0 THEN CURRENT_TIMESTAMP - INTERVAL '2 days' ELSE NULL END, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, course_id) DO UPDATE SET
                status = EXCLUDED.status,
                progress_percent = EXCLUDED.progress_percent,
                completed_at = EXCLUDED.completed_at,
                updated_at = CURRENT_TIMESTAMP
            RETURNING id INTO v_enrollment_id;

            FOR v_lesson_id IN
                SELECT l.id FROM lessons l JOIN topics t ON t.id = l.topic_id WHERE t.course_id = v_course_id ORDER BY l.order_index, l.id LIMIT 3
            LOOP
                INSERT INTO lesson_progress (user_id, course_enrollment_id, lesson_id, status, progress_percent, started_at, completed_at, created_at, updated_at)
                VALUES (v_student_id, v_enrollment_id, v_lesson_id, 'COMPLETED', 100, CURRENT_TIMESTAMP - INTERVAL '10 days', CURRENT_TIMESTAMP - INTERVAL '8 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (course_enrollment_id, lesson_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    progress_percent = EXCLUDED.progress_percent,
                    completed_at = EXCLUDED.completed_at,
                    updated_at = CURRENT_TIMESTAMP;

                INSERT INTO quiz_attempts (user_id, course_enrollment_id, lesson_id, status, score_percent, earned_xp, passed, submitted_at, created_at, updated_at)
                VALUES (v_student_id, v_enrollment_id, v_lesson_id, 'SUBMITTED', 70 + (student_index % 25), 10, TRUE, CURRENT_TIMESTAMP - INTERVAL '7 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id INTO v_attempt_id;

                FOR answer_question IN SELECT q.id, qo.id AS option_id FROM questions q JOIN question_options qo ON qo.question_id = q.id AND qo.is_correct WHERE q.lesson_id = v_lesson_id ORDER BY q.order_index LIMIT 3 LOOP
                    INSERT INTO question_answers (quiz_attempt_id, question_id, answer_text, selected_option_id, correct, score_percent, earned_xp, grading_method, feedback)
                    VALUES (v_attempt_id, answer_question.id, NULL, answer_question.option_id, TRUE, 100, 5, 'OPTION_MATCH', 'Correct answer with a useful review note.');
                END LOOP;

                INSERT INTO learning_activities (user_id, source_type, source_id, activity_date, xp_earned)
                VALUES (v_student_id, 'LESSON_QUIZ_PASS', v_attempt_id, CURRENT_DATE - (student_index % 5), 10)
                ON CONFLICT (user_id, source_type, source_id, activity_date) DO UPDATE SET xp_earned = EXCLUDED.xp_earned;
            END LOOP;
        END LOOP;

        INSERT INTO placement_attempts (user_id, status, started_at, submitted_at, expires_at, score_percent, created_at, updated_at)
        VALUES (v_student_id, 'SUBMITTED', CURRENT_TIMESTAMP - INTERVAL '14 days', CURRENT_TIMESTAMP - INTERVAL '14 days', CURRENT_TIMESTAMP + INTERVAL '14 days', 55 + (student_index % 35), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO v_attempt_id;

        FOR answer_question IN
            SELECT q.id, l.skill_type AS skill, row_number() OVER (ORDER BY q.id) AS sequence_order
            FROM questions q JOIN lessons l ON l.id = q.lesson_id
            WHERE l.is_placement_test = TRUE
              AND l.skill_type IN ('VOCABULARY', 'GRAMMAR', 'READING', 'WRITING')
            ORDER BY q.id LIMIT 16
        LOOP
            INSERT INTO placement_attempt_questions (placement_attempt_id, question_id, sequence_order, skill)
            VALUES (v_attempt_id, answer_question.id, answer_question.sequence_order, answer_question.skill);
        END LOOP;

        SELECT path.value INTO recommendation_path
        FROM jsonb_array_elements(v_recommendation_paths) AS path(value)
        WHERE path.value->>'studentEmail' = student->>'email';

        INSERT INTO course_recommendation_paths (user_id, status, active_user_id, weaknesses_json, source, generated_at, created_at, updated_at)
        VALUES (v_student_id, 'ACTIVE', v_student_id, recommendation_path->>'weaknessesJson', 'AI_SERVICE', CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO v_recommendation_path_id;

        FOR recommendation_item IN SELECT * FROM jsonb_array_elements(recommendation_path->'items') LOOP
            SELECT c.id INTO v_course_id FROM courses c WHERE c.name = recommendation_item->>'courseName';
            INSERT INTO course_recommendation_items (path_id, course_id, sequence_order, reason, score, created_at, updated_at)
            VALUES (v_recommendation_path_id, v_course_id, (recommendation_item->>'sequenceOrder')::INT, recommendation_item->>'reason', (recommendation_item->>'score')::NUMERIC, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
        END LOOP;

        INSERT INTO grammar_checks (user_id, status, source, input_text, completed_at, created_at, updated_at)
        VALUES (v_student_id, 'SUCCEEDED', 'AI_SERVICE', 'I have worked on my English homework every evening this week.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO v_check_id;
        INSERT INTO grammar_check_errors (check_id, sequence_order, error_text, error_type, suggestion, explanation, start_pos, end_pos)
        VALUES (v_check_id, 1, 'I has', 'GRAMMAR', 'I have', 'Use have with the subject I.', 0, 5);

        INSERT INTO ai_conversations (user_id, title, practice_type, status, started_at, last_message_at, cleanup_warn_at, archive_after, created_at, updated_at)
        VALUES (v_student_id, 'AI Tutor Practice ' || student_index, 'FREE_CHAT', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '25 days', CURRENT_TIMESTAMP + INTERVAL '30 days', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO v_conversation_id;
        INSERT INTO ai_messages (conversation_id, sender_type, message, created_at)
        VALUES (v_conversation_id, 'USER', 'I want practice English today.', CURRENT_TIMESTAMP)
        RETURNING id INTO v_message_id;
        INSERT INTO ai_feedback (message_id, error_type, original_text, corrected_text, explanation)
        VALUES (v_message_id, 'GRAMMAR', 'practice English', 'practice English', 'Helpful feedback for continuing practice.');
        INSERT INTO ai_messages (conversation_id, sender_type, message, created_at)
        VALUES (v_conversation_id, 'AI', 'Great. Let us practice with a short daily conversation.', CURRENT_TIMESTAMP);
    END LOOP;
END
"""
    do_block = _dollar_quote(body, "learne_realistic_seed_do")
    return f"""DO {do_block};

-- Content summary: {len(seed['teachers'])} teachers, {len(seed['students'])} students, {course_count} courses, {lesson_count} lessons, {question_count} questions.
"""


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Build the realistic full platform data SQL")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    seed = build_seed(load_manifest(args.manifest))
    sql = render_sql(seed)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(sql, encoding="utf-8")
    course_count, lesson_count, question_count = _counts(seed)
    print(
        f"Wrote {args.output} with {course_count} courses, {lesson_count} lessons, {question_count} questions"
    )


if __name__ == "__main__":
    main()

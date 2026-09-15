import json
import re
from pathlib import Path

import build_realistic_seed as seed_builder


def _manifest_path():
    return Path(__file__).resolve().parents[1] / "database" / "realistic-content" / "listening-manifest.json"


def _rendered_sql():
    manifest = seed_builder.load_manifest(_manifest_path())
    seed = seed_builder.build_seed(manifest)
    return seed, seed_builder.render_sql(seed)


def _extract_courses_json(sql):
    literal = sql.split("v_courses JSONB := ", 1)[1].split(";", 1)[0]
    quoted = literal.rsplit("::jsonb", 1)[0]
    delimiter = quoted.split("$", 2)
    delimiter = f"${delimiter[1]}$"
    return json.loads(quoted[len(delimiter) : -len(delimiter)])


def _all_lessons(seed):
    return [
        lesson
        for course in seed["courses"]
        for topic in course["topics"]
        for lesson in topic["lessons"]
    ]


def _all_questions(seed):
    return [
        question
        for lesson in _all_lessons(seed)
        for question in lesson["questions"]
    ]


def _generated_text_values(value):
    if isinstance(value, dict):
        for child in value.values():
            yield from _generated_text_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from _generated_text_values(child)
    elif isinstance(value, str):
        yield value


def test_build_seed_generates_required_realistic_volume():
    manifest = seed_builder.load_manifest(_manifest_path())
    seed = seed_builder.build_seed(manifest)
    lesson_count = sum(len(topic["lessons"]) for course in seed["courses"] for topic in course["topics"])
    question_count = sum(
        len(lesson["questions"])
        for course in seed["courses"]
        for topic in course["topics"]
        for lesson in topic["lessons"]
    )

    assert len(seed["teachers"]) == 5
    assert len(seed["students"]) == 30
    assert len(seed["courses"]) == 24
    assert lesson_count == 631
    assert question_count == 6510
    placement_questions = [
        question
        for course in seed["courses"]
        for topic in course["topics"]
        for lesson in topic["lessons"]
        if lesson["isPlacementTest"]
        for question in lesson["questions"]
    ]
    assert len(placement_questions) == 240
    assert {q["skill"] for q in placement_questions} == {"VOCABULARY", "GRAMMAR", "READING", "WRITING"}
    assert all(
        len([q for q in placement_questions if q["skill"] == skill]) == 60
        for skill in ("VOCABULARY", "GRAMMAR", "READING", "WRITING")
    )


def test_build_seed_generates_active_course_recommendations_for_every_student():
    manifest = seed_builder.load_manifest(_manifest_path())
    seed = seed_builder.build_seed(manifest)

    recommendation_paths = seed["courseRecommendationPaths"]
    recommendation_items = [
        item
        for path in recommendation_paths
        for item in path["items"]
    ]
    course_by_name = {course["name"]: course for course in seed["courses"]}

    assert len(recommendation_paths) == 30
    assert len(recommendation_items) == 120
    assert [path["studentEmail"] for path in recommendation_paths] == [
        student["email"] for student in seed["students"]
    ]
    assert {path["status"] for path in recommendation_paths} == {"ACTIVE"}
    assert {path["source"] for path in recommendation_paths} == {"AI_SERVICE"}
    assert all(len(path["items"]) == 4 for path in recommendation_paths)
    assert all(
        not any(
            lesson["isPlacementTest"]
            for topic in course_by_name[item["courseName"]]["topics"]
            for lesson in topic["lessons"]
        )
        for item in recommendation_items
    )


def test_build_seed_uses_realistic_local_identities():
    manifest = seed_builder.load_manifest(_manifest_path())
    seed = seed_builder.build_seed(manifest)

    assert [(teacher["email"], teacher["fullName"]) for teacher in seed["teachers"]] == [
        ("nguyen.minh.ha@learne.local", "Nguyen Minh Ha"),
        ("tran.quoc.anh@learne.local", "Tran Quoc Anh"),
        ("pham.linh.chi@learne.local", "Pham Linh Chi"),
        ("le.hoang.nam@learne.local", "Le Hoang Nam"),
        ("doan.thu.trang@learne.local", "Doan Thu Trang"),
    ]
    assert [student["email"] for student in seed["students"]] == [
        f"student{i:02d}@learne.local" for i in range(1, 31)
    ]
    assert [student["fullName"] for student in seed["students"]] == [
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
    ]
    assert all(len(student["fullName"].split()) >= 3 for student in seed["students"])
    forbidden_sample_label = "de" + "mo"
    assert all(forbidden_sample_label not in student["fullName"].lower() for student in seed["students"])


def test_build_seed_generates_expected_course_structure():
    manifest = seed_builder.load_manifest(_manifest_path())
    seed = seed_builder.build_seed(manifest)

    for course in seed["courses"]:
        lessons = [lesson for topic in course["topics"] for lesson in topic["lessons"]]
        if course["type"] == "LISTENING":
            assert len(lessons) == 32
        elif course["type"] == "COMPREHENSIVE":
            assert len(course["topics"]) == 9
            assert all(len(topic["lessons"]) == 4 for topic in course["topics"])
            assert len(lessons) == 36
        else:
            regular_lessons = [lesson for lesson in lessons if not lesson["isPlacementTest"]]
            assert len(course["topics"]) == 7
            assert all(len([lesson for lesson in topic["lessons"] if not lesson["isPlacementTest"]]) == 3 for topic in course["topics"])
            assert len(regular_lessons) == 21

    non_placement_lessons = [lesson for lesson in _all_lessons(seed) if not lesson["isPlacementTest"]]
    assert all(len(lesson["questions"]) == 10 for lesson in non_placement_lessons)


def test_build_seed_maps_all_listening_manifest_items_to_audio_lessons():
    manifest = seed_builder.load_manifest(_manifest_path())
    seed = seed_builder.build_seed(manifest)

    listening_lessons = [
        lesson
        for course in seed["courses"]
        if course["type"] == "LISTENING"
        for topic in course["topics"]
        for lesson in topic["lessons"]
    ]

    assert len(listening_lessons) == 96
    assert all(lesson["audioUrl"] for lesson in listening_lessons)
    assert all(lesson["audioPublicId"] for lesson in listening_lessons)
    assert len({lesson["audioUrl"] for lesson in listening_lessons}) == 96
    assert len({lesson["audioPublicId"] for lesson in listening_lessons}) == 96
    manifest_titles = {item["lessonTitle"] for item in manifest["items"]}
    assert {lesson["title"] for lesson in listening_lessons}.issubset(manifest_titles)
    assert {
        course["name"]: sum(len(topic["lessons"]) for topic in course["topics"])
        for course in seed["courses"]
        if course["type"] == "LISTENING"
    } == {
        "A1 Listening Everyday English": 32,
        "A2 Listening in Real Situations": 32,
        "B1 Listening for Details": 32,
    }
    for course in seed["courses"]:
        if course["type"] == "LISTENING":
            course_titles = {
                item["lessonTitle"]
                for item in manifest["items"]
                if item["courseName"] == course["name"]
            }
            assert {
                lesson["title"]
                for topic in course["topics"]
                for lesson in topic["lessons"]
            }.issubset(course_titles)


def test_build_seed_maps_reordered_listening_manifest_items_by_course_metadata():
    manifest = seed_builder.load_manifest(_manifest_path())
    manifest["items"] = list(reversed(manifest["items"]))

    seed = seed_builder.build_seed(manifest)

    listening_courses = {
        course["name"]: [
            lesson["title"]
            for topic in course["topics"]
            for lesson in topic["lessons"]
        ]
        for course in seed["courses"]
        if course["type"] == "LISTENING"
    }
    manifest_titles_by_course = {
        course_name: {
            item["lessonTitle"]
            for item in manifest["items"]
            if item["courseName"] == course_name
        }
        for course_name in listening_courses
    }

    assert {course_name: len(titles) for course_name, titles in listening_courses.items()} == {
        "A1 Listening Everyday English": 32,
        "A2 Listening in Real Situations": 32,
        "B1 Listening for Details": 32,
    }
    assert set(listening_courses["A1 Listening Everyday English"]).issubset(manifest_titles_by_course["A1 Listening Everyday English"])
    assert set(listening_courses["A2 Listening in Real Situations"]).issubset(manifest_titles_by_course["A2 Listening in Real Situations"])
    assert set(listening_courses["B1 Listening for Details"]).issubset(manifest_titles_by_course["B1 Listening for Details"])


def test_rendered_sql_omits_legacy_sample_and_speech_values():
    _, sql = _rendered_sql()
    forbidden_speech_term = "pron" + "unciation"
    forbidden_sample_label = "De" + "mo Rich"
    forbidden_sample_email = "de" + "mo.rich"

    assert forbidden_sample_label not in sql
    assert forbidden_sample_email not in sql
    assert forbidden_speech_term not in sql.lower()


def test_generated_content_omits_seed_artifacts():
    seed, sql = _rendered_sql()
    generated_text = "\n".join(_generated_text_values(seed)).lower()
    generated_sql = sql.lower()
    forbidden_speech_term = "pron" + "unciation"
    forbidden_sample_term = "de" + "mo"
    forbidden_sample_name = forbidden_sample_term + " rich"
    forbidden_sample_email = forbidden_sample_term + ".rich"
    forbidden_legacy_folder = "listening-" + forbidden_sample_term

    assert forbidden_sample_term not in generated_sql
    for forbidden in (
        forbidden_sample_name,
        forbidden_sample_email,
        forbidden_speech_term,
        "local/dev",
        "seed content",
        "production-like",
        "seeded feedback item",
        forbidden_legacy_folder,
    ):
        assert forbidden not in generated_text
        assert forbidden not in generated_sql


def test_generated_questions_have_complete_answer_keys_and_options():
    seed, _ = _rendered_sql()

    for question in _all_questions(seed):
        correct_answer = question.get("correctAnswer", "")
        options = question.get("options", [])

        assert correct_answer.strip()
        assert len(options) >= 4
        assert options.count(correct_answer) == 1


def test_generated_quiz_content_omits_generic_answer_artifacts():
    seed, sql = _rendered_sql()
    content = "\n".join(_generated_text_values(seed)) + "\n" + sql

    for pattern in (
        r"answer \d+",
        r"item \d+",
        r"choose the best .* answer",
        r"Review the clue",
        r"Review the context clue",
        r"Choose another detail",
        r"Not supported by context",
    ):
        assert not re.search(pattern, content, re.IGNORECASE)


def test_generated_realistic_sql_omits_demo_debug_and_speech_content():
    seed, sql = _rendered_sql()
    content = "\n".join(_generated_text_values(seed)) + "\n" + sql

    assert not re.search(r"demo", content, re.IGNORECASE)
    assert not re.search(r"debug", content, re.IGNORECASE)
    assert not re.search(r"pronunciation", content, re.IGNORECASE)


def test_rendered_sql_contains_idempotent_lookup_patterns():
    _, sql = _rendered_sql()

    assert "ON CONFLICT (email) DO UPDATE" in sql
    assert "WHERE c.name =" in sql
    assert "WHERE t.course_id = v_course_id AND t.name =" in sql
    assert "WHERE l.topic_id = v_topic_id AND l.title =" in sql


def test_rendered_sql_resets_business_data_while_preserving_admin_users():
    _, sql = _rendered_sql()

    assert "IF to_regclass('public.ai_feedback') IS NOT NULL THEN" in sql
    assert "DELETE FROM ai_feedback" in sql
    assert "IF to_regclass('public.ai_messages') IS NOT NULL THEN" in sql
    assert "DELETE FROM ai_messages" in sql
    assert "IF to_regclass('public.ai_conversations') IS NOT NULL THEN" in sql
    assert "DELETE FROM ai_conversations" in sql
    assert "IF to_regclass('public.grammar_check_errors') IS NOT NULL THEN" in sql
    assert "DELETE FROM grammar_check_errors" in sql
    assert "IF to_regclass('public.grammar_checks') IS NOT NULL THEN" in sql
    assert "DELETE FROM grammar_checks" in sql
    assert "DELETE FROM learning_path_items" in sql
    assert "DELETE FROM learning_paths" in sql
    assert "DELETE FROM courses" in sql
    assert "DELETE FROM users WHERE role <> 'ADMIN'" in sql
    assert "TRUNCATE" not in sql


def test_rendered_sql_creates_course_recommendation_paths_and_items():
    _, sql = _rendered_sql()

    assert "INSERT INTO course_recommendation_paths" in sql
    assert "INSERT INTO course_recommendation_items" in sql
    assert "'ACTIVE', v_student_id, recommendation_path->>'weaknessesJson', 'AI_SERVICE'" in sql
    assert "FOR recommendation_item IN SELECT * FROM jsonb_array_elements(recommendation_path->'items') LOOP" in sql


def test_reset_sql_deletes_learning_paths_before_dependent_content_and_users():
    _, sql = _rendered_sql()

    learning_path_items_delete = sql.index("DELETE FROM learning_path_items")
    learning_paths_delete = sql.index("DELETE FROM learning_paths")
    lessons_delete = sql.index("DELETE FROM lessons")
    courses_delete = sql.index("DELETE FROM courses")
    users_delete = sql.index("DELETE FROM users WHERE role <> 'ADMIN'")

    assert learning_path_items_delete < learning_paths_delete
    assert learning_path_items_delete < lessons_delete
    assert learning_paths_delete < users_delete
    assert lessons_delete < courses_delete < users_delete


def test_reset_sql_deletes_course_recommendation_items_before_paths():
    _, sql = _rendered_sql()

    recommendation_items_delete = sql.index("DELETE FROM course_recommendation_items")
    recommendation_paths_delete = sql.index("DELETE FROM course_recommendation_paths")
    users_delete = sql.index("DELETE FROM users WHERE role <> 'ADMIN'")

    assert recommendation_items_delete < recommendation_paths_delete < users_delete


def test_rendered_sql_omits_generator_verification_comments():
    _, sql = _rendered_sql()

    assert "Verification:" not in sql
    assert "SELECT COUNT(*) FROM courses" not in sql


def test_manifest_validation_supports_realistic_listening_catalog():
    manifest = seed_builder.load_manifest(_manifest_path())

    assert len(manifest["items"]) == 96
    assert {item["courseName"] for item in manifest["items"]} == {
        "A1 Listening Everyday English",
        "A2 Listening in Real Situations",
        "B1 Listening for Details",
    }
    assert {
        course_name: len([
            item for item in manifest["items"] if item["courseName"] == course_name
        ])
        for course_name in {item["courseName"] for item in manifest["items"]}
    } == {
        "A1 Listening Everyday English": 32,
        "A2 Listening in Real Situations": 32,
        "B1 Listening for Details": 32,
    }


def test_build_seed_rejects_short_listening_manifest_course():
    manifest = seed_builder.load_manifest(_manifest_path())
    manifest["items"] = [
        item
        for item in manifest["items"]
        if item["id"] != "listen-a2-travel-plan-17"
    ]

    try:
        seed_builder.build_seed(manifest)
    except ValueError as exc:
        assert "A2 Listening in Real Situations" in str(exc)
        assert "requires 32 unique listening manifest items" in str(exc)
    else:
        raise AssertionError("build_seed should reject a short listening manifest course")


def test_rendered_sql_uses_explicit_valid_placement_attempt_skills():
    _, sql = _rendered_sql()

    assert "upper(split_part(answer_question.knowledge_point, ' ', 2))" not in sql
    assert "answer_question.skill" in sql
    assert "l.skill_type AS skill" in sql
    assert "l.skill_type IN ('VOCABULARY', 'GRAMMAR', 'READING', 'WRITING')" in sql


def test_rendered_sql_carries_non_empty_manifest_audio_fields():
    manifest = seed_builder.load_manifest(_manifest_path())
    manifest["items"][0]["audioUrl"] = "https://res.cloudinary.com/audio/video/upload/listen-a1-practice.mp3"
    manifest["items"][0]["audioPublicId"] = "learne/listening-practice/listen-a1-practice"

    seed = seed_builder.build_seed(manifest)
    sql = seed_builder.render_sql(seed)

    assert "https://res.cloudinary.com/audio/video/upload/listen-a1-practice.mp3" in sql
    assert "learne/listening-practice/listen-a1-practice" in sql


def test_rendered_sql_uses_collision_safe_dollar_quotes_for_generated_content():
    manifest = seed_builder.load_manifest(_manifest_path())
    manifest["items"][0]["lessonTitle"] = "Listening: O'Brien\n$$ Path \\ tiếng Việt"
    manifest["items"][0]["audioPublicId"] = "learne/listening-audio/o'brien-$$-path"

    seed = seed_builder.build_seed(manifest)
    sql = seed_builder.render_sql(seed)
    courses = _extract_courses_json(sql)
    first_listening_lesson = next(
        lesson
        for course in courses
        if course["type"] == "LISTENING"
        for topic in course["topics"]
        for lesson in topic["lessons"]
        if lesson["audioPublicId"] == "learne/listening-audio/o'brien-$$-path"
    )

    assert "DO $$" not in sql
    assert first_listening_lesson["title"] == "Listening: O'Brien\n$$ Path \\ tiếng Việt"
    assert first_listening_lesson["audioPublicId"] == "learne/listening-audio/o'brien-$$-path"
    assert sql.count("DO $learne_realistic_seed_do") == 1
    do_delimiter = sql.split("DO ", 1)[1].split("\n", 1)[0]
    body = sql.split(do_delimiter, 1)[1].rsplit(do_delimiter, 1)[0]
    assert do_delimiter not in body


def test_main_writes_seed_file_and_prints_counts(tmp_path, capsys):
    output_file = tmp_path / "seed-realistic-full.sql"

    seed_builder.main(["--manifest", str(_manifest_path()), "--output", str(output_file)])

    captured = capsys.readouterr()
    written = output_file.read_text(encoding="utf-8")

    assert "24 courses" in captured.out
    assert "questions" in captured.out
    assert "A1 Grammar Foundations" in written
    assert ("De" + "mo Rich") not in written
    assert _extract_courses_json(written)

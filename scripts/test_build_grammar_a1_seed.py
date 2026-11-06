import re
import json
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
RESET_SQL = ROOT / "database" / "reset-local-learning-seed.sql"
BATCH_PATH = ROOT / "database" / "seed_batches" / "grammar-a1-batch-1.json"
BATCH_2_PATH = ROOT / "database" / "seed_batches" / "grammar-a1-batch-2.json"
BATCH_3_PATH = ROOT / "database" / "seed_batches" / "grammar-a1-batch-3.json"
BATCH_3_SQL_PATH = ROOT / "database" / "seed_batches" / "grammar-a1-batch-3.sql"


def load_batch(path=BATCH_PATH):
    return json.loads(path.read_text(encoding="utf-8"))


def normalize(value):
    return " ".join(value.casefold().split())


def all_questions(batch):
    return [
        question
        for course in batch["courses"]
        for topic in course["topics"]
        for lesson in topic["lessons"]
        for question in lesson["questions"]
    ]


def question_signature(question):
    return (
        normalize(question["correctAnswer"]),
        tuple(sorted(normalize(option["text"]) for option in question["options"])),
    )


def test_batch_3_contains_two_teacher_specific_a1_grammar_courses():
    batch = load_batch(BATCH_3_PATH)
    assert len(batch["courses"]) == 2
    courses = {course["teacherEmail"]: course for course in batch["courses"]}
    assert set(courses) == {
        "le.hoang.nam@learne.local",
        "doan.thu.trang@learne.local",
    }
    assert courses["le.hoang.nam@learne.local"]["name"] == (
        "A1 Grammar: Discover Daily Routines with Le Hoang Nam"
    )
    assert courses["doan.thu.trang@learne.local"]["name"] == (
        "A1 Grammar: My Family and Things with Doan Thu Trang"
    )
    for course in courses.values():
        assert (course["type"], course["level"]) == ("GRAMMAR", "A1")
        assert len(course["topics"]) == 2
        lessons = [lesson for topic in course["topics"] for lesson in topic["lessons"]]
        assert len(lessons) == 2
        assert all(len(topic["lessons"]) == 1 for topic in course["topics"])
        assert all(len(lesson["questions"]) == 5 for lesson in lessons)
        for lesson in lessons:
            for question in lesson["questions"]:
                assert len(question["options"]) == 4
                assert sum(option["isCorrect"] for option in question["options"]) == 1
                assert question["correctAnswer"] in {
                    option["text"] for option in question["options"]
                }
                assert question["explanation"]
                assert question["knowledgePoint"]
                assert question["difficulty"] == "easy"


def test_batch_3_has_no_exact_prompt_or_answer_option_duplicates_across_all_batches():
    batches = [load_batch(path) for path in (BATCH_PATH, BATCH_2_PATH, BATCH_3_PATH)]
    prompt_sets = [
        {normalize(question["questionText"]) for question in all_questions(batch)}
        for batch in batches
    ]
    signature_sets = [
        {question_signature(question) for question in all_questions(batch)}
        for batch in batches
    ]
    assert len(prompt_sets[2]) == 20
    assert len(signature_sets[2]) == 20
    assert prompt_sets[2].isdisjoint(prompt_sets[0])
    assert prompt_sets[2].isdisjoint(prompt_sets[1])
    assert signature_sets[2].isdisjoint(signature_sets[0])
    assert signature_sets[2].isdisjoint(signature_sets[1])


def test_batch_2_is_pham_linh_chi_a1_grammar_with_two_lessons():
    batch = load_batch(BATCH_2_PATH)
    assert len(batch["courses"]) == 1
    course = batch["courses"][0]
    assert course["teacherEmail"] == "pham.linh.chi@learne.local"
    assert course["name"] == "A1 Grammar: Spot the Error with Pham Linh Chi"
    assert (course["type"], course["level"]) == ("GRAMMAR", "A1")
    lessons = [lesson for topic in course["topics"] for lesson in topic["lessons"]]
    assert len(course["topics"]) == 2
    assert len(lessons) == 2
    assert all(len(lesson["questions"]) == 5 for lesson in lessons)
    for question in all_questions(batch):
        assert len(question["options"]) == 4
        assert sum(option["isCorrect"] for option in question["options"]) == 1
        assert question["correctAnswer"] in {
            option["text"] for option in question["options"]
        }
        assert question["explanation"]
        assert question["knowledgePoint"]
        assert question["difficulty"] == "easy"


def test_batch_2_has_no_exact_prompt_or_answer_option_duplicates_with_batch_1():
    first_questions = all_questions(load_batch())
    second_questions = all_questions(load_batch(BATCH_2_PATH))
    prompt = lambda question: normalize(question["questionText"])
    signature = lambda question: (
        normalize(question["correctAnswer"]),
        tuple(sorted(normalize(option["text"]) for option in question["options"])),
    )

    second_prompts = {prompt(question) for question in second_questions}
    first_prompts = {prompt(question) for question in first_questions}
    second_signatures = {signature(question) for question in second_questions}
    first_signatures = {signature(question) for question in first_questions}

    assert len(second_questions) == 10
    assert len(second_prompts) == 10
    assert second_prompts.isdisjoint(first_prompts)
    assert second_signatures.isdisjoint(first_signatures)


def test_local_reset_is_transactional_and_preserves_accounts_and_unrelated_data():
    sql = RESET_SQL.read_text(encoding="utf-8").lower()

    assert "begin;" in sql
    assert "commit;" in sql
    for table in (
        "users",
        "grammar_checks",
        "grammar_check_errors",
        "orders",
        "order_items",
        "refresh_tokens",
    ):
        assert not re.search(rf"delete\s+from\s+(public\.)?{table}\b", sql)
    assert "truncate" not in sql


def test_local_reset_deletes_learning_children_before_content_parents():
    sql = RESET_SQL.read_text(encoding="utf-8").lower()

    assert sql.index("delete from question_answers") < sql.index("delete from questions")
    assert sql.index("delete from learning_activities") < sql.index("delete from quiz_attempts")
    assert "source_id in (select id from _reset_quiz_attempt_ids)" in sql
    assert sql.index("delete from questions") < sql.index("delete from lessons")
    assert sql.index("delete from lessons") < sql.index("delete from topics")
    assert sql.index("delete from topics") < sql.index("delete from courses")


def test_local_reset_excludes_courses_referenced_by_commerce_orders():
    sql = RESET_SQL.read_text(encoding="utf-8").lower()

    course_scope = sql.split("create temp table _reset_course_ids", 1)[1].split(";", 1)[0]
    assert "order_items" in course_scope
    assert "course_entitlements" in course_scope
    assert "coupon_course_scopes" in course_scope
    assert "vip_course_selections" in course_scope
    assert "not exists" in course_scope or "not in" in course_scope


def test_reset_handles_historical_review_rows_without_relying_on_a_course_fk():
    sql = RESET_SQL.read_text(encoding="utf-8").lower()

    assert "delete from course_review_decisions" in sql


def test_batch_has_two_distinct_teacher_owned_courses():
    batch = load_batch()
    courses = batch["courses"]

    assert {course["teacherEmail"] for course in courses} == {
        "nguyen.minh.ha@learne.local",
        "tran.quoc.anh@learne.local",
    }
    assert len(courses) == 2
    assert len({normalize(course["name"]) for course in courses}) == 2


def test_each_course_has_two_lessons_and_five_unique_questions_per_lesson():
    batch = load_batch()
    prompts = []
    option_sets = []

    for course in batch["courses"]:
        lessons = [lesson for topic in course["topics"] for lesson in topic["lessons"]]
        assert len(lessons) == 2
        assert all(len(lesson["questions"]) == 5 for lesson in lessons)
        for lesson in lessons:
            for question in lesson["questions"]:
                prompts.append(normalize(question["questionText"]))
                options = question["options"]
                assert len(options) == 4
                assert sum(option["isCorrect"] for option in options) == 1
                assert question["correctAnswer"] in {
                    option["text"] for option in options
                }
                assert question["explanation"]
                assert question["knowledgePoint"]
                assert question["difficulty"] == "easy"
                option_sets.append(
                    (
                        normalize(question["correctAnswer"]),
                        tuple(sorted(normalize(option["text"]) for option in options)),
                    )
                )

    assert len(prompts) == 20
    assert len(set(prompts)) == 20
    assert len(set(option_sets)) == 20


def test_rendered_sql_is_scoped_and_deterministic(tmp_path):
    from build_grammar_a1_seed import load_batch, render_sql

    sql = render_sql(load_batch(BATCH_PATH))
    assert "BEGIN;" in sql
    assert "COMMIT;" in sql
    assert "teacherEmail" not in sql
    assert "nguyen.minh.ha@learne.local" in sql
    assert "tran.quoc.anh@learne.local" in sql
    assert "DELETE FROM courses;" not in sql
    assert "DELETE FROM users" not in sql
    assert sql.count("INSERT INTO questions") == 20
    assert sql.count("INSERT INTO question_options") == 80
    assert sql.count("INSERT INTO course_revisions") == 2
    assert sql.count("INSERT INTO topic_revisions") == 4
    assert sql.count("INSERT INTO lesson_revisions") == 4
    assert sql.count("INSERT INTO question_revisions") == 20
    assert sql.count("INSERT INTO question_option_revisions (question_revision_id,source_option_id,option_text,is_correct,order_index) VALUES") == 80
    assert sql.count("INSERT INTO question_option_revisions (question_revision_id,source_option_id,option_text,is_correct,order_index) VALUES") == 80
    assert sql.count("INSERT INTO question_option_revisions (question_revision_id,source_option_id,option_text,is_correct,order_index) SELECT") == 20
    assert "teacher_id = teacher_id" not in sql
    assert "lesson_id = lesson_id" not in sql
    assert "SELECT q.id INTO v_question_id FROM questions q" in sql
    assert "DELETE FROM questions WHERE lesson_id=lesson_id" not in sql
    assert "INSERT INTO course_revisions" in sql
    assert "INSERT INTO topic_revisions" in sql
    assert "INSERT INTO lesson_revisions" in sql
    assert "INSERT INTO question_revisions" in sql
    assert "INSERT INTO question_option_revisions" in sql
    assert "IF NOT EXISTS (SELECT 1 FROM topic_revisions WHERE revision_id=v_revision_id) THEN" in sql
    assert "DELETE FROM topic_revisions" not in sql
    assert "DELETE FROM question_options WHERE question_id=v_question_id;" not in sql
    assert "IF NOT EXISTS (SELECT 1 FROM question_options" in sql

    output_a = tmp_path / "a.sql"
    output_b = tmp_path / "b.sql"
    command = ["python", "scripts/build_grammar_a1_seed.py", "--output", str(output_a)]
    subprocess.run(command, cwd=ROOT, check=True)
    command[-1] = str(output_b)
    subprocess.run(command, cwd=ROOT, check=True)
    assert output_a.read_bytes() == output_b.read_bytes()


def test_batch_2_sql_is_scoped_and_deterministic(tmp_path):
    output_a = tmp_path / "batch-2-a.sql"
    output_b = tmp_path / "batch-2-b.sql"
    command = [
        "python",
        "scripts/build_grammar_a1_seed.py",
        "--input",
        str(BATCH_2_PATH),
        "--output",
        str(output_a),
    ]

    subprocess.run(command, cwd=ROOT, check=True)
    command[-1] = str(output_b)
    subprocess.run(command, cwd=ROOT, check=True)

    sql = output_a.read_text(encoding="utf-8")
    assert sql == output_b.read_text(encoding="utf-8")
    assert sql.count("INSERT INTO courses") == 1
    assert sql.count("INSERT INTO topics") == 2
    assert sql.count("INSERT INTO lessons") == 2
    assert sql.count("INSERT INTO questions") == 10
    assert sql.count("INSERT INTO question_options") == 40
    assert sql.count("INSERT INTO course_revisions") == 1
    assert sql.count("INSERT INTO topic_revisions") == 2
    assert sql.count("INSERT INTO lesson_revisions") == 2
    assert sql.count("INSERT INTO question_revisions") == 10
    assert "A1 Grammar: Spot the Error with Pham Linh Chi" in sql
    assert "pham.linh.chi@learne.local" in sql
    assert "A1 Grammar: Clear Rules with Nguyen Minh Ha" not in sql
    assert "A1 Grammar: English in Everyday Dialogues with Tran Quoc Anh" not in sql
    assert "DELETE FROM" not in sql


def test_batch_3_sql_is_scoped_and_deterministic(tmp_path):
    assert BATCH_3_SQL_PATH.exists()
    output_a = tmp_path / "batch-3-a.sql"
    output_b = tmp_path / "batch-3-b.sql"
    command = [
        "python",
        "scripts/build_grammar_a1_seed.py",
        "--input",
        str(BATCH_3_PATH),
        "--output",
        str(output_a),
    ]

    subprocess.run(command, cwd=ROOT, check=True)
    command[-1] = str(output_b)
    subprocess.run(command, cwd=ROOT, check=True)

    sql = output_a.read_text(encoding="utf-8")
    assert sql == output_b.read_text(encoding="utf-8")
    assert sql.count("INSERT INTO courses") == 2
    assert sql.count("INSERT INTO topics") == 4
    assert sql.count("INSERT INTO lessons") == 4
    assert sql.count("INSERT INTO questions") == 20
    assert sql.count("INSERT INTO question_options") == 80
    assert sql.count("INSERT INTO course_revisions") == 2
    assert sql.count("INSERT INTO topic_revisions") == 4
    assert sql.count("INSERT INTO lesson_revisions") == 4
    assert sql.count("INSERT INTO question_revisions") == 20
    assert "A1 Grammar: Discover Daily Routines with Le Hoang Nam" in sql
    assert "A1 Grammar: My Family and Things with Doan Thu Trang" in sql
    assert "le.hoang.nam@learne.local" in sql
    assert "doan.thu.trang@learne.local" in sql
    assert "A1 Grammar: Clear Rules with Nguyen Minh Ha" not in sql
    assert "A1 Grammar: English in Everyday Dialogues with Tran Quoc Anh" not in sql
    assert "A1 Grammar: Spot the Error with Pham Linh Chi" not in sql
    assert "DELETE FROM" not in sql


def test_validation_rejects_answer_that_is_not_the_correct_option():
    from build_grammar_a1_seed import validate_batch

    batch = load_batch()
    question = batch["courses"][0]["topics"][0]["lessons"][0]["questions"][0]
    question["correctAnswer"] = "not an option"

    with pytest.raises(ValueError, match="correctAnswer"):
        validate_batch(batch)


def test_validation_rejects_topics_without_exactly_one_lesson():
    from build_grammar_a1_seed import validate_batch

    batch = load_batch(BATCH_2_PATH)
    batch["courses"][0]["topics"][0]["lessons"].append(
        batch["courses"][0]["topics"][1]["lessons"][0].copy()
    )
    batch["courses"][0]["topics"][1]["lessons"] = []

    with pytest.raises(ValueError, match="exactly one lesson"):
        validate_batch(batch)


def test_pronoun_lesson_does_not_test_untaught_present_continuous():
    batch = load_batch()
    lesson = batch["courses"][1]["topics"][0]["lessons"][0]

    assert all("doing" not in question["questionText"].casefold() for question in lesson["questions"])

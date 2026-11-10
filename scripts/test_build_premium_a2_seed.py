import json
import re
import unicodedata
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SYLLABUS_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-pilot-syllabus.json"
BATCH_1_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-1.json"
BATCH_2_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-2.json"
BATCH_3_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-3.json"
BATCH_4_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-4.json"
BATCH_5_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-5.json"
BATCH_6_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-6.json"
BATCH_7_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-7.json"
BATCH_8_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-8.json"
A1_BATCH_PATHS = [
    ROOT / "database" / "seed_batches" / "grammar-a1-batch-1.json",
    ROOT / "database" / "seed_batches" / "grammar-a1-batch-2.json",
    ROOT / "database" / "seed_batches" / "grammar-a1-batch-3.json",
]
SQL_PATH = ROOT / "database" / "seed_batches" / "grammar-a2-batch-1.sql"


def load_syllabus(path=SYLLABUS_PATH):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def load_batch(path=BATCH_1_PATH):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def normalize(value):
    value = unicodedata.normalize("NFKC", value).casefold()
    return re.sub(r"\s+", " ", value).strip()


def question_signature(question):
    options = tuple(
        (normalize(option["text"]), bool(option["isCorrect"]))
        for option in question["options"]
    )
    return normalize(question["questionText"]), options


def test_a2_batch_1_sql_is_additive_draft_free_and_deterministic(tmp_path):
    from build_premium_a2_seed import render_sql

    batch = load_batch()
    first = render_sql(batch)
    second = render_sql(batch)

    assert first == second
    assert first.count("INSERT INTO courses") == 1
    assert first.count("INSERT INTO topics") == 1
    assert first.count("INSERT INTO lessons") == 4
    assert first.count("INSERT INTO questions") == 40
    assert first.count("INSERT INTO question_options") == 160
    assert first.count("INSERT INTO course_revisions") == 1
    assert first.count("INSERT INTO topic_revisions") == 1
    assert first.count("INSERT INTO lesson_revisions") == 4
    assert first.count("INSERT INTO question_revisions") == 40
    assert first.count("INSERT INTO question_option_revisions") == 160
    assert "DELETE " not in first.upper()
    assert "TRUNCATE " not in first.upper()
    assert "Batch 1" not in first and "Batch 2" not in first and "Batch 3" not in first
    assert "'DRAFT'" in first
    assert "'FREE'" in first
    assert "price_amount_vnd=NULL" in first
    assert "pricing_type='FREE'" in first
    assert "status='DRAFT'" in first


def test_a2_batch_1_sql_persists_all_lesson_contract_fields():
    from build_premium_a2_seed import render_sql

    sql = render_sql(load_batch())

    for lesson in load_batch()["lessons"]:
        assert lesson["objective"] in sql
        assert lesson["guidedPractice"] in sql
        assert lesson["summary"] in sql
        for example in lesson["examples"]:
            assert example in sql
        for error in lesson["commonErrors"]:
            assert error in sql


def test_a2_batch_1_sql_protects_existing_non_draft_revisions():
    from build_premium_a2_seed import render_sql

    sql = render_sql(load_batch())

    assert "IF v_revision_id IS NOT NULL" in sql
    assert "v_existing_course_status" in sql
    assert "v_existing_revision_status" in sql
    assert "RAISE EXCEPTION 'Existing A2 course is not DRAFT/FREE'" in sql
    assert "RAISE EXCEPTION 'Existing A2 revision is not DRAFT/FREE'" in sql
    assert "RAISE EXCEPTION 'Existing A2 revision has a different topic batch'" in sql


def test_a2_syllabus_has_eight_ordered_topics_with_valid_prerequisites():
    syllabus = load_syllabus()

    assert syllabus["teacherEmail"] == "nguyen.minh.ha@learne.local"
    assert syllabus["level"] == "A2"
    assert syllabus["type"] == "GRAMMAR"

    topics = syllabus["topics"]
    assert len(topics) == 8
    assert [topic["orderIndex"] for topic in topics] == list(range(1, 9))

    ids = [topic["id"] for topic in topics]
    names = [topic["name"] for topic in topics]
    assert len(set(ids)) == 8
    assert len(set(names)) == 8
    for index, topic in enumerate(topics):
        assert topic["id"].strip()
        assert topic["name"].strip()
        assert topic["objective"].strip()
        assert set(topic["prerequisites"]).issubset(set(ids[:index]))


def test_a2_batch_1_has_four_complete_lessons_and_forty_questions():
    batch = load_batch()

    assert batch["course"]["teacherEmail"] == "nguyen.minh.ha@learne.local"
    assert batch["course"]["level"] == "A2"
    assert batch["course"]["type"] == "GRAMMAR"
    assert batch["topicId"] == load_syllabus()["topics"][0]["id"]
    assert len(batch["lessons"]) == 4
    assert sum(len(lesson["questions"]) for lesson in batch["lessons"]) == 40

    for lesson in batch["lessons"]:
        for field in (
            "objective",
            "content",
            "examples",
            "commonErrors",
            "guidedPractice",
            "summary",
        ):
            assert lesson[field]
        assert len(lesson["questions"]) == 10
        for question in lesson["questions"]:
            assert question["type"] == "MULTIPLE_CHOICE"
            assert question["knowledgePoint"].strip()
            assert question["difficulty"] in {"easy", "medium"}
            assert question["correctAnswer"].strip()
            assert question["explanation"].strip()
            assert len(question["options"]) >= 2
            assert sum(option["isCorrect"] for option in question["options"]) == 1


def test_a2_batch_1_questions_are_unique_against_each_a1_batch():
    batch = load_batch()
    questions = [
        question
        for lesson in batch["lessons"]
        for question in lesson["questions"]
    ]
    signatures = [question_signature(question) for question in questions]
    assert len(signatures) == len(set(signatures))

    a1_signatures = set()
    for path in A1_BATCH_PATHS:
        source = json.loads(path.read_text(encoding="utf-8"))
        for course in source["courses"]:
            for topic in course["topics"]:
                for lesson in topic["lessons"]:
                    a1_signatures.update(
                        question_signature(question)
                        for question in lesson["questions"]
                    )
    assert not set(signatures) & a1_signatures


def test_a2_batch_2_has_four_past_simple_lessons_and_forty_valid_questions():
    batch = load_batch(BATCH_2_PATH)

    assert batch["topicId"] == load_syllabus()["topics"][1]["id"]
    assert batch["topicName"] == "Past Simple"
    assert batch["course"]["teacherEmail"] == "nguyen.minh.ha@learne.local"
    assert len(batch["lessons"]) == 4
    assert sum(len(lesson["questions"]) for lesson in batch["lessons"]) == 40
    for lesson in batch["lessons"]:
        for field in ("objective", "content", "examples", "commonErrors", "guidedPractice", "summary"):
            assert lesson[field]
        assert len(lesson["questions"]) == 10
        for question in lesson["questions"]:
            assert question["type"] == "MULTIPLE_CHOICE"
            assert question["knowledgePoint"]
            assert question["difficulty"] in {"easy", "medium"}
            assert question["explanation"]
            assert len(question["options"]) == 4
            assert sum(option["isCorrect"] for option in question["options"]) == 1
            assert question["correctAnswer"] in {option["text"] for option in question["options"]}


def test_a2_batch_2_has_no_exact_question_duplicates_with_batch_1_or_a1():
    batch = load_batch(BATCH_2_PATH)
    questions = [question for lesson in batch["lessons"] for question in lesson["questions"]]
    signatures = [question_signature(question) for question in questions]
    assert len(signatures) == 40
    assert len(signatures) == len(set(signatures))

    earlier_signatures = set()
    for path in [BATCH_1_PATH, *A1_BATCH_PATHS]:
        source = json.loads(path.read_text(encoding="utf-8"))
        if "courses" in source:
            source_questions = [
                question
                for course in source["courses"]
                for topic in course["topics"]
                for lesson in topic["lessons"]
                for question in lesson["questions"]
            ]
        else:
            source_questions = [
                question
                for lesson in source["lessons"]
                for question in lesson["questions"]
            ]
        earlier_signatures.update(question_signature(question) for question in source_questions)
    assert set(signatures).isdisjoint(earlier_signatures)


def test_a2_batch_2_sql_appends_to_existing_draft_revision_only():
    from build_premium_a2_seed import render_batch_2_sql

    batch = load_batch(BATCH_2_PATH)
    sql = render_batch_2_sql(batch)

    assert sql == render_batch_2_sql(batch)
    assert "A2 Grammar: Everyday Accuracy with Nguyen Minh Ha" in sql
    assert "Present Simple and Present Continuous" in sql
    assert "Past Simple" in sql
    assert "INSERT INTO courses" not in sql
    assert "INSERT INTO course_revisions" not in sql
    assert sql.count("INSERT INTO topics") == 1
    assert sql.count("INSERT INTO topic_revisions") == 1
    assert sql.count("INSERT INTO lessons") == 4
    assert sql.count("INSERT INTO lesson_revisions") == 4
    assert sql.count("INSERT INTO questions") == 40
    assert sql.count("INSERT INTO question_options") == 160
    assert sql.count("INSERT INTO question_revisions") == 40
    assert sql.count("INSERT INTO question_option_revisions") == 160
    assert "DRAFT/FREE" in sql
    assert "DELETE FROM" not in sql.upper()
    assert "TRUNCATE" not in sql.upper()


def test_a2_batch_3_is_past_continuous_and_follows_existing_topics():
    batch = load_batch(BATCH_3_PATH)

    assert batch["topicId"] == load_syllabus()["topics"][2]["id"]
    assert batch["topicName"] == "Past Continuous and Interrupted Events"
    assert batch["course"]["teacherEmail"] == "nguyen.minh.ha@learne.local"
    assert len(batch["lessons"]) == 4
    assert all(len(lesson["questions"]) == 10 for lesson in batch["lessons"])
    assert sum(len(lesson["questions"]) for lesson in batch["lessons"]) == 40


def test_a2_batch_3_questions_are_unique_against_batches_1_2_and_a1():
    batch = load_batch(BATCH_3_PATH)
    questions = [question for lesson in batch["lessons"] for question in lesson["questions"]]
    signatures = [question_signature(question) for question in questions]
    assert len(signatures) == 40
    assert len(signatures) == len(set(signatures))

    earlier_signatures = set()
    for path in [BATCH_1_PATH, BATCH_2_PATH, *A1_BATCH_PATHS]:
        source = json.loads(path.read_text(encoding="utf-8"))
        if "courses" in source:
            source_questions = [
                question
                for course in source["courses"]
                for topic in course["topics"]
                for lesson in topic["lessons"]
                for question in lesson["questions"]
            ]
        else:
            source_questions = [
                question
                for lesson in source["lessons"]
                for question in lesson["questions"]
            ]
        earlier_signatures.update(question_signature(question) for question in source_questions)
    assert set(signatures).isdisjoint(earlier_signatures)


def test_a2_batch_3_sql_appends_without_rewriting_prior_topics():
    from build_premium_a2_seed import render_batch_3_sql

    batch = load_batch(BATCH_3_PATH)
    sql = render_batch_3_sql(batch)

    assert sql == render_batch_3_sql(batch)
    assert "Present Simple and Present Continuous" in sql
    assert "Past Simple" in sql
    assert "Past Continuous and Interrupted Events" in sql
    assert "INSERT INTO courses" not in sql
    assert "INSERT INTO course_revisions" not in sql
    assert "UPDATE courses" not in sql
    assert "UPDATE course_revisions" not in sql
    assert sql.count("INSERT INTO topics") == 1
    assert sql.count("INSERT INTO topic_revisions") == 1
    assert sql.count("INSERT INTO lessons") == 4
    assert sql.count("INSERT INTO lesson_revisions") == 4
    assert sql.count("INSERT INTO questions") == 40
    assert sql.count("INSERT INTO question_options") == 160
    assert sql.count("INSERT INTO question_revisions") == 40
    assert sql.count("INSERT INTO question_option_revisions") == 160
    assert "DRAFT/FREE" in sql
    assert "DELETE FROM" not in sql.upper()
    assert "TRUNCATE" not in sql.upper()


def test_completed_earlier_batch_can_rerun_after_later_topics_exist():
    from build_premium_a2_seed import render_incremental_batch_sql

    batch4 = load_batch(ROOT / "database" / "seed_batches" / "grammar-a2-batch-4.json")
    sql = render_incremental_batch_sql(
        batch4,
        4,
        [
            "Present Simple and Present Continuous",
            "Past Simple",
            "Past Continuous and Interrupted Events",
        ],
    )

    assert "v_topic_count < 3" in sql
    assert "IF v_topic_count < 3 OR v_revision_question_count < 120 THEN RAISE EXCEPTION 'A2 pilot prior topics are incomplete'" in sql


def test_a2_batch_2_is_past_simple_and_follows_topic_1():
    batch = load_batch(BATCH_2_PATH)

    assert batch["topicId"] == load_syllabus()["topics"][1]["id"]
    assert batch["topicName"] == "Past Simple"
    assert batch["course"]["teacherEmail"] == "nguyen.minh.ha@learne.local"
    assert len(batch["lessons"]) == 4
    assert all(len(lesson["questions"]) == 10 for lesson in batch["lessons"])
    assert sum(len(lesson["questions"]) for lesson in batch["lessons"]) == 40


def test_a2_batch_2_questions_are_unique_against_batch_1_and_a1():
    batch = load_batch(BATCH_2_PATH)
    signatures = [
        question_signature(question)
        for lesson in batch["lessons"]
        for question in lesson["questions"]
    ]
    assert len(signatures) == 40
    assert len(signatures) == len(set(signatures))

    earlier_signatures = set()
    for path in [BATCH_1_PATH, *A1_BATCH_PATHS]:
        source = json.loads(path.read_text(encoding="utf-8"))
        if "courses" in source:
            earlier_signatures.update(
                question_signature(question)
                for course in source["courses"]
                for topic in course["topics"]
                for lesson in topic["lessons"]
                for question in lesson["questions"]
            )
        else:
            earlier_signatures.update(
                question_signature(question)
                for lesson in source["lessons"]
                for question in lesson["questions"]
            )
    assert set(signatures).isdisjoint(earlier_signatures)


def test_a2_batch_2_sql_is_incremental_and_idempotent():
    from build_premium_a2_seed import render_batch_2_sql

    batch = load_batch(BATCH_2_PATH)
    sql = render_batch_2_sql(batch)

    assert sql == render_batch_2_sql(batch)
    assert "course_revisions" in sql
    assert "revision_number=1" in sql or "revision_number = 1" in sql
    assert "Present Simple and Present Continuous" in sql
    assert "Past Simple" in sql
    assert "IF NOT EXISTS" in sql
    assert "DELETE FROM" not in sql.upper()
    assert "TRUNCATE" not in sql.upper()
    assert sql.count("INSERT INTO topics") == 1
    assert sql.count("INSERT INTO lessons") == 4
    assert sql.count("INSERT INTO questions") == 40
    assert sql.count("INSERT INTO question_options") == 160


def _questions_from_batch(source):
    return [
        question
        for lesson in source["lessons"]
        for question in lesson["questions"]
    ]


def test_batches_4_to_6_match_syllabus_topics_and_dependencies():
    syllabus = load_syllabus()
    batches = [load_batch(path) for path in (BATCH_4_PATH, BATCH_5_PATH, BATCH_6_PATH)]

    for batch, topic in zip(batches, syllabus["topics"][3:6], strict=True):
        assert batch["topicId"] == topic["id"]
        assert batch["topicName"] == topic["name"]
        assert batch["course"]["teacherEmail"] == "nguyen.minh.ha@learne.local"

    assert syllabus["topics"][4]["prerequisites"] == [
        "present-simple-present-continuous",
        "future-going-to-will",
    ]
    assert syllabus["topics"][5]["prerequisites"] == [
        "countable-uncountable-quantifiers"
    ]


def test_batches_4_to_6_have_complete_lesson_and_question_contracts():
    for path in (BATCH_4_PATH, BATCH_5_PATH, BATCH_6_PATH):
        batch = load_batch(path)
        assert len(batch["lessons"]) == 4
        assert sum(len(lesson["questions"]) for lesson in batch["lessons"]) == 40

        for lesson in batch["lessons"]:
            for field in (
                "objective",
                "description",
                "content",
                "examples",
                "commonErrors",
                "guidedPractice",
                "summary",
            ):
                assert lesson[field]
            assert len(lesson["questions"]) == 10
            for question in lesson["questions"]:
                assert question["type"] == "MULTIPLE_CHOICE"
                assert len(question["options"]) == 4
                assert sum(option["isCorrect"] for option in question["options"]) == 1
                assert question["correctAnswer"] in {
                    option["text"] for option in question["options"]
                }
                assert question["explanation"].strip()
                assert question["knowledgePoint"].strip()
                assert question["difficulty"] in {"easy", "medium"}


def test_batches_4_to_6_questions_are_unique_across_all_prior_batches():
    new_questions = [
        question
        for path in (BATCH_4_PATH, BATCH_5_PATH, BATCH_6_PATH)
        for question in _questions_from_batch(load_batch(path))
    ]
    new_signatures = [question_signature(question) for question in new_questions]
    assert len(new_signatures) == 120
    assert len(new_signatures) == len(set(new_signatures))

    earlier_signatures = set()
    for path in [BATCH_1_PATH, BATCH_2_PATH, BATCH_3_PATH, *A1_BATCH_PATHS]:
        source = json.loads(path.read_text(encoding="utf-8"))
        if "courses" in source:
            source_questions = [
                question
                for course in source["courses"]
                for topic in course["topics"]
                for lesson in topic["lessons"]
                for question in lesson["questions"]
            ]
        else:
            source_questions = _questions_from_batch(source)
        earlier_signatures.update(
            question_signature(question) for question in source_questions
        )

    assert set(new_signatures).isdisjoint(earlier_signatures)


def test_batches_4_to_6_have_safe_append_only_renderers():
    from build_premium_a2_seed import (
        render_batch_4_sql,
        render_batch_5_sql,
        render_batch_6_sql,
    )

    for batch, renderer, topic_name, prior_name in (
        (load_batch(BATCH_4_PATH), render_batch_4_sql, "Future Plans and Predictions", "Past Continuous and Interrupted Events"),
        (load_batch(BATCH_5_PATH), render_batch_5_sql, "Countable and Uncountable Nouns", "Future Plans and Predictions"),
        (load_batch(BATCH_6_PATH), render_batch_6_sql, "Comparatives and Superlatives", "Countable and Uncountable Nouns"),
    ):
        sql = renderer(batch)
        assert sql == renderer(batch)
        assert topic_name in sql
        assert prior_name in sql
        assert "INSERT INTO courses" not in sql
        assert "INSERT INTO course_revisions" not in sql
        assert "UPDATE courses" not in sql
        assert "UPDATE course_revisions" not in sql
        assert sql.count("INSERT INTO topics") == 1
        assert sql.count("INSERT INTO topic_revisions") == 1
        assert sql.count("INSERT INTO lessons") == 4
        assert sql.count("INSERT INTO lesson_revisions") == 4
        assert sql.count("INSERT INTO questions") == 40
        assert sql.count("INSERT INTO question_options") == 160
        assert sql.count("INSERT INTO question_revisions") == 40
        assert sql.count("INSERT INTO question_option_revisions") == 160
        assert "DRAFT/FREE" in sql
        assert "DELETE " not in sql.upper()
        assert "TRUNCATE " not in sql.upper()


def test_batch_7_personal_obligation_question_has_one_defensible_answer():
    questions = _questions_from_batch(load_batch(BATCH_7_PATH))
    question = next(
        question
        for question in questions
        if question["questionText"].startswith("I decided that I")
    )

    assert "have to" not in {option["text"] for option in question["options"]}


def test_batches_7_and_8_match_syllabus_and_prerequisites():
    syllabus = load_syllabus()
    batch7 = load_batch(BATCH_7_PATH)
    batch8 = load_batch(BATCH_8_PATH)

    assert batch7["topicId"] == syllabus["topics"][6]["id"]
    assert batch7["topicName"] == syllabus["topics"][6]["name"]
    assert batch8["topicId"] == syllabus["topics"][7]["id"]
    assert batch8["topicName"] == syllabus["topics"][7]["name"]
    assert "modals-obligation-advice-permission" in syllabus["topics"][7]["prerequisites"]
    for batch in (batch7, batch8):
        assert batch["course"]["name"] == "A2 Grammar: Everyday Accuracy with Nguyen Minh Ha"
        assert batch["course"]["teacherEmail"] == "nguyen.minh.ha@learne.local"


def test_batches_7_and_8_have_complete_lesson_and_question_contracts():
    for path in (BATCH_7_PATH, BATCH_8_PATH):
        batch = load_batch(path)
        assert len(batch["lessons"]) == 4
        assert sum(len(lesson["questions"]) for lesson in batch["lessons"]) == 40

        for lesson in batch["lessons"]:
            for field in (
                "objective",
                "description",
                "content",
                "examples",
                "commonErrors",
                "guidedPractice",
                "summary",
            ):
                assert lesson[field]
            assert len(lesson["questions"]) == 10
            for question in lesson["questions"]:
                assert question["type"] == "MULTIPLE_CHOICE"
                assert len(question["options"]) == 4
                assert sum(option["isCorrect"] for option in question["options"]) == 1
                assert question["correctAnswer"] in {
                    option["text"] for option in question["options"]
                }
                assert question["explanation"].strip()
                assert question["knowledgePoint"].strip()
                assert question["difficulty"] in {"easy", "medium"}


def test_batches_7_and_8_questions_are_unique_across_all_prior_batches():
    new_questions = [
        question
        for path in (BATCH_7_PATH, BATCH_8_PATH)
        for question in _questions_from_batch(load_batch(path))
    ]
    new_signatures = [question_signature(question) for question in new_questions]
    assert len(new_signatures) == 80
    assert len(new_signatures) == len(set(new_signatures))

    earlier_signatures = set()
    for path in [
        BATCH_1_PATH,
        BATCH_2_PATH,
        BATCH_3_PATH,
        BATCH_4_PATH,
        BATCH_5_PATH,
        BATCH_6_PATH,
        *A1_BATCH_PATHS,
    ]:
        source = json.loads(path.read_text(encoding="utf-8"))
        if "courses" in source:
            source_questions = [
                question
                for course in source["courses"]
                for topic in course["topics"]
                for lesson in topic["lessons"]
                for question in lesson["questions"]
            ]
        else:
            source_questions = _questions_from_batch(source)
        earlier_signatures.update(
            question_signature(question) for question in source_questions
        )

    assert set(new_signatures).isdisjoint(earlier_signatures)


def test_batches_7_and_8_have_safe_append_only_renderers():
    from build_premium_a2_seed import render_batch_7_sql, render_batch_8_sql

    for batch, renderer, prerequisites, topic_name in (
        (
            load_batch(BATCH_7_PATH),
            render_batch_7_sql,
            [
                "Present Simple and Present Continuous",
                "Past Simple",
                "Past Continuous and Interrupted Events",
                "Future Plans and Predictions",
                "Countable and Uncountable Nouns",
                "Comparatives and Superlatives",
            ],
            "Modals for Obligation, Advice, and Permission",
        ),
        (
            load_batch(BATCH_8_PATH),
            render_batch_8_sql,
            [
                "Present Simple and Present Continuous",
                "Past Simple",
                "Past Continuous and Interrupted Events",
                "Future Plans and Predictions",
                "Countable and Uncountable Nouns",
                "Comparatives and Superlatives",
                "Modals for Obligation, Advice, and Permission",
            ],
            "Present Perfect for Experience and Unfinished Time",
        ),
    ):
        sql = renderer(batch)
        assert sql == renderer(batch)
        assert topic_name in sql
        assert "v_prerequisite_option_count" in sql
        assert "v_own_option_count" in sql
        assert "question_option_revisions qor" in sql
        for prerequisite in prerequisites:
            assert prerequisite in sql
        assert "INSERT INTO courses" not in sql
        assert "INSERT INTO course_revisions" not in sql
        assert "UPDATE courses" not in sql
        assert "UPDATE course_revisions" not in sql
        assert sql.count("INSERT INTO topics") == 1
        assert sql.count("INSERT INTO topic_revisions") == 1
        assert sql.count("INSERT INTO lessons") == 4
        assert sql.count("INSERT INTO lesson_revisions") == 4
        assert sql.count("INSERT INTO questions") == 40
        assert sql.count("INSERT INTO question_options") == 160
        assert sql.count("INSERT INTO question_revisions") == 40
        assert sql.count("INSERT INTO question_option_revisions") == 160
        assert "DRAFT/FREE" in sql
        assert "DELETE " not in sql.upper()
        assert "TRUNCATE " not in sql.upper()


def test_batch_4_choice_contexts_match_their_will_or_going_to_answers():
    lesson = load_batch(BATCH_4_PATH)["lessons"][3]
    expected = {
        "We planned it last week.": ("We ___ tomorrow.", "are going to meet"),
        "I decide now to help you.": ("I ___ now.", "will help"),
        "Look at those clouds.": ("It ___ soon.", "is going to rain"),
        "I promise to call tonight.": ("I ___ tonight.", "will call"),
        "She bought paint yesterday.": ("She ___ the room.", "is going to paint"),
        "The kettle is shaking.": ("It ___ over.", "is going to boil over"),
        "The doorbell rings.": ("I ___ it.", "will open"),
        "They arranged a holiday.": ("They ___ next month.", "are going to travel"),
        "He offers to carry the box.": ("He ___ it.", "will carry"),
        "The child is holding a broken cup.": ("It ___ soon.", "is going to drop it"),
    }
    for question in lesson["questions"]:
        cue = question["questionText"].split(" Choose the best completion:")[0]
        ending, answer = expected[cue]
        assert question["questionText"].endswith(ending)
        assert question["correctAnswer"] == answer

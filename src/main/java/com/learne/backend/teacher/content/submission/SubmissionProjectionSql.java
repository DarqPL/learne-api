package com.learne.backend.teacher.content.submission;

/** Each event carries only its own stored fields; ancestor text is never repeated by a join. */
final class SubmissionProjectionSql {
    private SubmissionProjectionSql() { }
    private static final String HEADER_COLUMNS = """
            select r.course_id,r.id,r.revision_number,r.teacher_id,r.status,r.name,r.description,
                   r.type,r.level,r.order_index,r.pricing_type,r.proposed_price_amount_vnd,
            """;
    static final String HEADER = HEADER_COLUMNS + """
                   (select count(*) from question_revisions q join lesson_revisions l on l.id=q.lesson_revision_id
                    join topic_revisions t on t.id=l.topic_revision_id where t.revision_id=r.id) question_count
            from course_revisions r
            """;
    // Private batch adapter sentinel: count is unused by hashing and never exposed by BatchContext.
    static final String BATCH_HEADER = HEADER_COLUMNS + "-1 as question_count from course_revisions r";
    static final String EVENTS = """
            with root as (select ?::bigint revision_id), events as (
              select array[t.order_index::bigint,t.id,0] path, 'topic' kind,
                     t.id,t.source_topic_id source_id,t.revision_id parent_id,t.order_index,
                     t.name a,t.description b,null::text c,null::text d,null::text e,null::text f,
                     null::int xp,null::boolean flag
              from topic_revisions t,root where t.revision_id=root.revision_id
              union all
              select array[t.order_index::bigint,t.id,1,l.order_index,l.id,0], 'lesson',
                     l.id,l.source_lesson_id,l.topic_revision_id,l.order_index,
                     l.title,l.description,l.content,l.skill_type,l.audio_url,l.audio_public_id,l.xp_reward,l.is_placement_test
              from lesson_revisions l join topic_revisions t on t.id=l.topic_revision_id,root
              where t.revision_id=root.revision_id
              union all
              select array[t.order_index::bigint,t.id,1,l.order_index,l.id,1,q.order_index,q.id,0], 'question',
                     q.id,q.source_question_id,q.lesson_revision_id,q.order_index,
                     q.type,q.question_text,q.explanation,q.correct_answer,q.knowledge_point,q.difficulty,q.xp_reward,null::boolean
              from question_revisions q join lesson_revisions l on l.id=q.lesson_revision_id
              join topic_revisions t on t.id=l.topic_revision_id,root where t.revision_id=root.revision_id
              union all
              select array[t.order_index::bigint,t.id,1,l.order_index,l.id,1,q.order_index,q.id,1,o.order_index,o.id], 'option',
                     o.id,o.source_option_id,o.question_revision_id,o.order_index,
                     o.option_text,null::text,null::text,null::text,null::text,null::text,null::int,o.is_correct
              from question_option_revisions o join question_revisions q on q.id=o.question_revision_id
              join lesson_revisions l on l.id=q.lesson_revision_id join topic_revisions t on t.id=l.topic_revision_id,root
              where t.revision_id=root.revision_id
            ) select * from events order by path
            """;
    /** Fixed 25 bind slots; no supplied identifier or value becomes SQL text. */
    static final String BATCH_EVENTS = """
            with selected_questions as materialized (
              select q.* from question_revisions q
              join lesson_revisions l on l.id=q.lesson_revision_id
              join topic_revisions t on t.id=l.topic_revision_id
              where t.revision_id=? and q.id in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ), selected_lessons as materialized (
              select l.* from lesson_revisions l
              where exists (select 1 from selected_questions q where q.lesson_revision_id=l.id)
            ), selected_topics as materialized (
              select t.* from topic_revisions t
              where exists (select 1 from selected_lessons l where l.topic_revision_id=t.id)
            ), events as (
              select array[t.order_index::bigint,t.id,0] path, 'topic' kind,
                     t.id,t.source_topic_id source_id,t.revision_id parent_id,t.order_index,
                     t.name a,t.description b,null::text c,null::text d,null::text e,null::text f,
                     null::int xp,null::boolean flag
              from selected_topics t
              union all
              select array[t.order_index::bigint,t.id,1,l.order_index,l.id,0], 'lesson',
                     l.id,l.source_lesson_id,l.topic_revision_id,l.order_index,
                     l.title,l.description,l.content,l.skill_type,l.audio_url,l.audio_public_id,l.xp_reward,l.is_placement_test
              from selected_lessons l join selected_topics t on t.id=l.topic_revision_id
              union all
              select array[t.order_index::bigint,t.id,1,l.order_index,l.id,1,q.order_index,q.id,0], 'question',
                     q.id,q.source_question_id,q.lesson_revision_id,q.order_index,
                     q.type,q.question_text,q.explanation,q.correct_answer,q.knowledge_point,q.difficulty,q.xp_reward,null::boolean
              from selected_questions q join selected_lessons l on l.id=q.lesson_revision_id
              join selected_topics t on t.id=l.topic_revision_id
              union all
              select array[t.order_index::bigint,t.id,1,l.order_index,l.id,1,q.order_index,q.id,1,o.order_index,o.id], 'option',
                     o.id,o.source_option_id,o.question_revision_id,o.order_index,
                     o.option_text,null::text,null::text,null::text,null::text,null::text,null::int,o.is_correct
              from question_option_revisions o join selected_questions q on q.id=o.question_revision_id
              join selected_lessons l on l.id=q.lesson_revision_id join selected_topics t on t.id=l.topic_revision_id
            ) select * from events order by path
            """;
}

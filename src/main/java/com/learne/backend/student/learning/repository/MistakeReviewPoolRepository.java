package com.learne.backend.student.learning.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MistakeReviewPoolRepository {
    private final JdbcTemplate jdbcTemplate;

    public MistakeReviewPoolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<LatestEvent> findPageByUser(Long userId, Long cursorEventOrder,
                                            Long cursorQuestionId, int limit) {
        return jdbcTemplate.query(
                """
                with answer_events as (
                    select qa.question_id, qa.answer_event_order, qa.correct
                    from question_answers qa
                    join quiz_attempts attempt on attempt.id = qa.quiz_attempt_id
                    where attempt.user_id = ?
                    union all
                    select membership.source_question_id_snapshot, answer.answer_event_order, answer.correct
                    from mistake_review_answers answer
                    join mistake_review_session_questions membership
                      on membership.id = answer.session_question_id
                    join mistake_review_sessions session on session.id = membership.session_id
                    where session.user_id = ?
                ),
                ranked_events as (
                    select question_id, answer_event_order, correct,
                           row_number() over (
                               partition by question_id
                               order by answer_event_order desc
                           ) as event_rank
                    from answer_events
                )
                select ranked.question_id, ranked.answer_event_order, ranked.correct
                from ranked_events ranked
                join questions question on question.id = ranked.question_id
                join lessons lesson on lesson.id = question.lesson_id
                join topics topic on topic.id = lesson.topic_id
                join courses course on course.id = topic.course_id
                where ranked.event_rank = 1
                  and ranked.correct = false
                  and question.type <> 'PRONUNCIATION'
                  and question.status = 'APPROVED'
                  and lesson.status = 'APPROVED'
                  and course.status = 'APPROVED'
                  and (
                      course.level = 'A1'
                      or course.pricing_type = 'FREE'
                      or exists (
                          select 1
                          from course_entitlements entitlement
                          where entitlement.user_id = ?
                            and entitlement.course_id = course.id
                            and entitlement.revoked_at is null
                            and entitlement.starts_at <= CURRENT_TIMESTAMP
                            and (entitlement.ends_at is null or entitlement.ends_at > CURRENT_TIMESTAMP)
                      )
                      or exists (
                          select 1
                          from subscriptions subscription
                          where subscription.user_id = ?
                            and subscription.status = 'ACTIVE'
                            and subscription.starts_at <= CURRENT_TIMESTAMP
                            and subscription.ends_at > CURRENT_TIMESTAMP
                            and subscription.plan_code in ('PRO', 'PRO_PLUS')
                      )
                      or exists (
                          select 1
                          from subscriptions subscription
                          join vip_course_selections selection
                            on selection.subscription_id = subscription.id
                           and selection.user_id = subscription.user_id
                           and selection.course_id = course.id
                           and selection.replaced_at is null
                          where subscription.user_id = ?
                            and subscription.status = 'ACTIVE'
                            and subscription.starts_at <= CURRENT_TIMESTAMP
                            and subscription.plan_code = 'VIP'
                            and subscription.ends_at > CURRENT_TIMESTAMP
                      )
                      or exists (
                          select 1
                          from users student
                          join users teacher on teacher.id = course.teacher_id
                          where student.id = ?
                            and student.account_origin = 'TEACHER_CREATED'
                            and student.owner_teacher_id = teacher.id
                      )
                  )
                  and exists (
                      select 1
                      from course_enrollments enrollment
                      where enrollment.user_id = ?
                        and enrollment.course_id = course.id
                        and enrollment.status = 'ACTIVE'
                  )
                  and (
                      cast(? as bigint) is null
                      or ranked.answer_event_order < ?
                      or (ranked.answer_event_order = ? and ranked.question_id > ?)
                  )
                order by ranked.answer_event_order desc, ranked.question_id asc
                limit ?
                """,
                (resultSet, rowNum) -> new LatestEvent(
                        resultSet.getLong("question_id"),
                        resultSet.getLong("answer_event_order"),
                        resultSet.getBoolean("correct")),
                userId, userId,
                userId, userId, userId, userId, userId,
                cursorEventOrder, cursorEventOrder, cursorEventOrder, cursorQuestionId,
                limit);
    }

    public record LatestEvent(Long questionId, Long answerEventOrder, boolean correct) {
    }
}

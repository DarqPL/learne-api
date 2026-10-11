package com.learne.backend.teacher.content.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionOptionRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CourseReviewEnrichmentServiceTests {
    @Test
    void normalizesKnowledgePointToSnakeCase() {
        assertThat(CourseReviewEnrichmentService.normalizeKnowledgePoint(" Listening Dates "))
                .isEqualTo("listening_dates");
        assertThat(CourseReviewEnrichmentService.normalizeKnowledgePoint("pronunciation-th sounds!"))
                .isEqualTo("pronunciation_th_sounds");
    }

    @Test
    void rejectsBlankAndOversizedKnowledgePoint() {
        assertThatThrownBy(() -> CourseReviewEnrichmentService.normalizeKnowledgePoint(" !!! "))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CourseReviewEnrichmentService.normalizeKnowledgePoint("x".repeat(65)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsTooManyQuestionsBeforeAiCall() {
        AtomicInteger calls = new AtomicInteger();
        CourseReviewEnrichmentService service = new CourseReviewEnrichmentService(
                request -> {
                    calls.incrementAndGet();
                    return new CourseKnowledgePointResponse(List.of());
                },
                new QuestionDifficultyCalculator(),
                new QuestionXpPolicy());

        assertThatThrownBy(() -> service.buildSnapshot(revisionWithQuestionCount(201)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("quá nhiều câu hỏi");
        assertThat(calls).hasValue(0);
    }

    @Test
    void chunksRequestsInGroupsOfTwentyFive() {
        AtomicInteger calls = new AtomicInteger();
        CourseReviewEnrichmentService service = new CourseReviewEnrichmentService(
                request -> {
                    calls.incrementAndGet();
                    return new CourseKnowledgePointResponse(request.questions().stream()
                            .map(question -> new CourseKnowledgePointResponse.Item(
                                    question.questionId(), "test_point"))
                            .toList());
                },
                new QuestionDifficultyCalculator(),
                new QuestionXpPolicy());
        CourseRevision revision = revisionWithQuestionCount(26);
        CourseReviewEnrichmentService.CourseRevisionSnapshot snapshot = service.buildSnapshot(revision);

        service.enrichSnapshot(snapshot, revision);

        assertThat(calls).hasValue(2);
    }

    @Test
    void buildsSnapshotWithDeterministicQuestionOrder() {
        CourseReviewEnrichmentService service = new CourseReviewEnrichmentService(
                request -> new CourseKnowledgePointResponse(List.of()),
                new QuestionDifficultyCalculator(),
                new QuestionXpPolicy());
        CourseRevision revision = revisionWithQuestionCount(0);
        LessonRevision lesson = revision.getTopics().getFirst().getLessons().getFirst();
        QuestionRevision later = question(20L, 2);
        QuestionRevision earlier = question(10L, 1);
        when(lesson.getQuestions()).thenReturn(List.of(later, earlier));

        CourseReviewEnrichmentService.CourseRevisionSnapshot snapshot = service.buildSnapshot(revision);

        assertThat(snapshot.questions()).extracting(CourseReviewEnrichmentService.QuestionSnapshot::questionId)
                .containsExactly(10L, 20L);
    }

    @Test
    void sendsOnlyFirstEightNonBlankOptionsInOrder() {
        CourseReviewEnrichmentService service = new CourseReviewEnrichmentService(
                request -> new CourseKnowledgePointResponse(List.of()),
                new QuestionDifficultyCalculator(),
                new QuestionXpPolicy());
        CourseRevision revision = revisionWithQuestionCount(0);
        LessonRevision lesson = revision.getTopics().getFirst().getLessons().getFirst();
        QuestionRevision question = question(1L, 1);
        List<QuestionOptionRevision> options = new ArrayList<>();
        for (int index = 10; index >= 1; index--) {
            QuestionOptionRevision option = mock(QuestionOptionRevision.class);
            when(option.getId()).thenReturn((long) index);
            when(option.getOrderIndex()).thenReturn(index);
            when(option.getOptionText()).thenReturn("Option " + index);
            when(option.isCorrect()).thenReturn(index == 1);
            options.add(option);
        }
        when(question.getOptions()).thenReturn(options);
        when(lesson.getQuestions()).thenReturn(List.of(question));

        CourseReviewEnrichmentService.CourseRevisionSnapshot snapshot = service.buildSnapshot(revision);

        assertThat(snapshot.questions().getFirst().payload().options())
                .extracting(CourseKnowledgePointRequest.OptionPayload::optionText)
                .containsExactly("Option 1", "Option 2", "Option 3", "Option 4", "Option 5", "Option 6", "Option 7", "Option 8");
    }

    private CourseRevision revisionWithQuestionCount(int count) {
        CourseRevision revision = mock(CourseRevision.class);
        Course course = mock(Course.class);
        TopicRevision topic = mock(TopicRevision.class);
        LessonRevision lesson = mock(LessonRevision.class);
        List<QuestionRevision> questions = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            questions.add(question(id, (int) id));
        }
        when(revision.getCourse()).thenReturn(course);
        when(course.getId()).thenReturn(1L);
        when(revision.getId()).thenReturn(1L);
        when(revision.getRevisionNumber()).thenReturn(1);
        when(revision.getStatus()).thenReturn(CourseRevisionStatus.DRAFT);
        when(revision.getName()).thenReturn("Course");
        when(revision.getDescription()).thenReturn("");
        when(revision.getType()).thenReturn(CourseType.COMPREHENSIVE);
        when(revision.getLevel()).thenReturn("A2");
        when(revision.getTopics()).thenReturn(List.of(topic));
        when(topic.getId()).thenReturn(1L);
        when(topic.getName()).thenReturn("Topic");
        when(topic.getDescription()).thenReturn("");
        when(topic.getLessons()).thenReturn(List.of(lesson));
        when(lesson.getId()).thenReturn(1L);
        when(lesson.getTitle()).thenReturn("Lesson");
        when(lesson.getDescription()).thenReturn("");
        when(lesson.getContent()).thenReturn("Content");
        when(lesson.getSkillType()).thenReturn(SkillType.READING);
        when(lesson.getQuestions()).thenReturn(questions);
        return revision;
    }

    private QuestionRevision question(Long id, int orderIndex) {
        QuestionRevision question = mock(QuestionRevision.class);
        when(question.getId()).thenReturn(id);
        when(question.getType()).thenReturn(QuestionType.MULTIPLE_CHOICE);
        when(question.getQuestionText()).thenReturn("Question " + id);
        when(question.getOptions()).thenReturn(List.of());
        when(question.getOrderIndex()).thenReturn(orderIndex);
        return question;
    }
}

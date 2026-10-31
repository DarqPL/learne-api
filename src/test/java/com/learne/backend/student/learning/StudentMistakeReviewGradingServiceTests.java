package com.learne.backend.student.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.dto.SubmitQuestionAnswerRequest;
import com.learne.backend.student.learning.service.StudentMistakeReviewGradingService;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudentMistakeReviewGradingServiceTests {
    private final StudentMistakeReviewGradingService service = new StudentMistakeReviewGradingService();

    @Test
    void gradesTextAnswersUsingLessonQuizNormalization() {
        StudentMistakeReviewGradingService.Snapshot snapshot =
                new StudentMistakeReviewGradingService.Snapshot(
                        7L, "SHORT_ANSWER", "Hello world", List.of(), "Explanation");

        StudentMistakeReviewGradingService.Result result = service.grade(
                snapshot, new SubmitQuestionAnswerRequest(7L, null, "  HELLO   world "));

        assertEquals(true, result.correct());
        assertEquals(100, result.scorePercent());
    }

    @Test
    void rejectsAnswerThatMixesTextAndOption() {
        StudentMistakeReviewGradingService.Snapshot snapshot =
                new StudentMistakeReviewGradingService.Snapshot(
                        7L, "MULTIPLE_CHOICE", null,
                        List.of(new StudentMistakeReviewGradingService.Option(9L, "A", true)), null);

        assertThrows(ApiException.class, () -> service.grade(
                snapshot, new SubmitQuestionAnswerRequest(7L, 9L, "A")));
    }

    @Test
    void returnsCorrectOptionDisplayOnlyWithTheGradedResult() {
        StudentMistakeReviewGradingService.Snapshot snapshot =
                new StudentMistakeReviewGradingService.Snapshot(
                        7L, "MULTIPLE_CHOICE", null,
                        List.of(new StudentMistakeReviewGradingService.Option(9L, "A", true),
                                new StudentMistakeReviewGradingService.Option(10L, "B", false)), null);

        StudentMistakeReviewGradingService.Result result = service.grade(
                snapshot, new SubmitQuestionAnswerRequest(7L, 9L, null));

        assertEquals("A", result.correctAnswerDisplay());
    }

    @Test
    void rejectsOptionFromAnotherSnapshotQuestion() {
        StudentMistakeReviewGradingService.Snapshot snapshot =
                new StudentMistakeReviewGradingService.Snapshot(
                        8L, "MULTIPLE_CHOICE", null,
                        List.of(new StudentMistakeReviewGradingService.Option(9L, "A", true)), null);

        assertThrows(ApiException.class, () -> service.grade(
                snapshot, new SubmitQuestionAnswerRequest(8L, 10L, null)));
    }
}

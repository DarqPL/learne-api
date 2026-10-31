package com.learne.backend.student.learning.service;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.dto.SubmitQuestionAnswerRequest;
import com.learne.backend.student.learning.enums.GradingMethod;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StudentMistakeReviewGradingService {
    public Result grade(Snapshot snapshot, SubmitQuestionAnswerRequest answer) {
        if (!snapshot.questionId().equals(answer.questionId())) {
            throw invalid("Question does not belong to this review session.");
        }
        if (usesOptionGrading(snapshot)) {
            if (answer.selectedOptionId() == null || answer.answerText() != null) {
                throw invalid("Select exactly one option.");
            }
            Option option = snapshot.options().stream()
                    .filter(candidate -> candidate.id().equals(answer.selectedOptionId()))
                    .findFirst()
                    .orElseThrow(() -> invalid("Option does not belong to this question."));
            String correctOptionText = snapshot.options().stream()
                    .filter(Option::correct)
                    .map(Option::text)
                    .findFirst()
                    .orElse(null);
            return new Result(option.correct(), option.correct() ? 100 : 0,
                    GradingMethod.OPTION_MATCH, option.correct() ? "Correct." : "Incorrect.",
                    correctOptionText, snapshot.explanation());
        }
        if (answer.selectedOptionId() != null || answer.answerText() == null || answer.answerText().isBlank()) {
            throw invalid("Answer is required for every question.");
        }
        boolean correct = normalize(snapshot.correctAnswer()).equals(normalize(answer.answerText()));
        return new Result(correct, correct ? 100 : 0, GradingMethod.EXACT_MATCH,
                correct ? "Correct." : "Incorrect.", snapshot.correctAnswer(), snapshot.explanation());
    }

    private boolean usesOptionGrading(Snapshot snapshot) {
        return "MULTIPLE_CHOICE".equals(snapshot.questionType())
                || ("TRUE_FALSE".equals(snapshot.questionType()) && !snapshot.options().isEmpty());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public record Snapshot(Long questionId, String questionType, String correctAnswer,
                           List<Option> options, String explanation) {
    }

    public record Option(Long id, String text, boolean correct) {
    }

    public record Result(boolean correct, int scorePercent, GradingMethod gradingMethod,
                         String feedback, String correctAnswerDisplay, String explanation) {
    }
}

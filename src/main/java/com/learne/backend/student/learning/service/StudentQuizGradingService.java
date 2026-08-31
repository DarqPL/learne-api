package com.learne.backend.student.learning.service;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.dto.SubmitQuestionAnswerRequest;
import com.learne.backend.student.learning.dto.SubmitQuizRequest;
import com.learne.backend.student.learning.enums.GradingMethod;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.QuestionOption;
import com.learne.backend.teacher.content.enums.QuestionType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StudentQuizGradingService {
    public static final int PASS_PERCENT = 70;

    public StudentQuizGradingResult grade(Lesson lesson, List<Question> questions, SubmitQuizRequest request) {
        for (SubmitQuestionAnswerRequest answer : request.answers()) {
            if (answer == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Answer is required for every question.");
            }
        }
        Set<Long> answeredQuestionIds = request.answers().stream()
                .map(SubmitQuestionAnswerRequest::questionId)
                .collect(Collectors.toSet());
        if (answeredQuestionIds.size() != request.answers().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Answer is required for every question.");
        }

        Map<Long, Question> questionsById = questions.stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
        for (Long questionId : answeredQuestionIds) {
            Question question = questionsById.get(questionId);
            if (question == null || !question.getLesson().getId().equals(lesson.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Question does not belong to this lesson.");
            }
        }

        List<Question> gradableQuestions = questions.stream()
                .filter(this::isGradable)
                .toList();
        if (gradableQuestions.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Quiz has no gradable questions.");
        }
        if (!answeredQuestionIds.containsAll(gradableQuestions.stream().map(Question::getId).toList())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Answer is required for every question.");
        }

        Map<Long, SubmitQuestionAnswerRequest> answersByQuestionId = request.answers().stream()
                .collect(Collectors.toMap(SubmitQuestionAnswerRequest::questionId, Function.identity()));
        List<StudentQuestionGradingResult> results = questions.stream()
                .filter(question -> isGradable(question) || answersByQuestionId.containsKey(question.getId()))
                .map(question -> gradeQuestion(question, answersByQuestionId.get(question.getId())))
                .toList();
        long correctCount = results.stream()
                .filter(StudentQuestionGradingResult::gradable)
                .filter(StudentQuestionGradingResult::correct)
                .count();
        int scorePercent = (int) Math.round((correctCount * 100.0) / gradableQuestions.size());
        return new StudentQuizGradingResult(scorePercent, scorePercent >= PASS_PERCENT, 0, results);
    }

    private StudentQuestionGradingResult gradeQuestion(Question question, SubmitQuestionAnswerRequest answer) {
        if (!isGradable(question)) {
            return new StudentQuestionGradingResult(
                    question,
                    answer,
                    null,
                    false,
                    0,
                    0,
                    GradingMethod.MANUAL_TARGET_ONLY,
                    "Pronunciation feedback will be graded in a later AI phase.",
                    question.getCorrectAnswer(),
                    null,
                    false
            );
        }
        if (usesOptionGrading(question)) {
            QuestionOption selectedOption = question.getOptions().stream()
                    .filter(option -> option.getId().equals(answer.selectedOptionId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Option does not belong to this question."));
            boolean correct = selectedOption.isCorrect();
            return new StudentQuestionGradingResult(
                    question,
                    answer,
                    selectedOption,
                    correct,
                    correct ? 100 : 0,
                    0,
                    GradingMethod.OPTION_MATCH,
                    feedback(correct),
                    null,
                    correctOptionId(question),
                    true
            );
        }

        if (answer.answerText() == null || answer.answerText().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Answer is required for every question.");
        }
        boolean correct = normalize(question.getCorrectAnswer()).equals(normalize(answer.answerText()));
        return new StudentQuestionGradingResult(
                question,
                answer,
                null,
                correct,
                correct ? 100 : 0,
                0,
                GradingMethod.EXACT_MATCH,
                feedback(correct),
                question.getCorrectAnswer(),
                null,
                true
        );
    }

    private boolean isGradable(Question question) {
        return question.getType() != QuestionType.PRONUNCIATION;
    }

    private boolean usesOptionGrading(Question question) {
        return question.getType() == QuestionType.MULTIPLE_CHOICE
                || (question.getType() == QuestionType.TRUE_FALSE && !question.getOptions().isEmpty());
    }

    private Long correctOptionId(Question question) {
        return question.getOptions().stream()
                .filter(QuestionOption::isCorrect)
                .findFirst()
                .map(QuestionOption::getId)
                .orElse(null);
    }

    private String feedback(boolean correct) {
        return correct ? "Correct." : "Incorrect.";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}

package com.learne.backend.student.learning.dto;

import java.util.List;

public final class MistakeReviewDtos {
    private MistakeReviewDtos() {
    }

    public record Option(Long id, String text, int order) {
    }

    public record Question(Long questionId, String questionText, String questionType,
                           List<Option> options, Long courseId, String courseName,
                           Long lessonId, String lessonTitle) {
    }

    public record CreateResponse(Long sessionId, int questionCount, List<Question> questions) {
    }

    public record SubmitRequest(List<SubmitQuestionAnswerRequest> answers) {
    }

    public record AnswerResult(Long questionId, boolean correct, int scorePercent,
                               String feedback, String correctAnswer, String explanation) {
    }

    public record Result(Long sessionId, int questionCount, int scorePercent,
                         List<AnswerResult> answers) {
    }

    public record SessionResponse(Long sessionId, int questionCount, List<Question> questions,
                                  Integer scorePercent, List<AnswerResult> answers) {
    }
}

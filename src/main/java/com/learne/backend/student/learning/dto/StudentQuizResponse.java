package com.learne.backend.student.learning.dto;

import com.learne.backend.teacher.content.entity.Lesson;
import java.util.List;

public record StudentQuizResponse(
        Long enrollmentId,
        Long lessonId,
        String lessonTitle,
        String lessonContent,
        String audioUrl,
        String audioPublicId,
        int passPercent,
        Integer bestScorePercent,
        boolean alreadyPassed,
        List<StudentQuizQuestionResponse> questions
) {
    public static StudentQuizResponse from(Long enrollmentId, Lesson lesson, Integer bestScorePercent, boolean alreadyPassed, List<StudentQuizQuestionResponse> questions) {
        return new StudentQuizResponse(enrollmentId, lesson.getId(), lesson.getTitle(), lesson.getContent(), lesson.getAudioUrl(), lesson.getAudioPublicId(), 70, bestScorePercent, alreadyPassed, questions);
    }
}

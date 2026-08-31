package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LessonProgress;

public record StudentQuizProgressResponse(
        String lessonStatus,
        int enrollmentProgressPercent,
        Long unlockedNextLessonId
) {
    public static StudentQuizProgressResponse from(LessonProgress progress, CourseEnrollment enrollment, Long unlockedNextLessonId) {
        return new StudentQuizProgressResponse(
                progress.getStatus().name(),
                enrollment.getProgressPercent(),
                unlockedNextLessonId
        );
    }
}

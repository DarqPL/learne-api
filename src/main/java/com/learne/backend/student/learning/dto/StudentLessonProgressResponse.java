package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.LessonProgress;
import java.time.Instant;

public record StudentLessonProgressResponse(
        Long lessonId,
        String title,
        String content,
        String skillType,
        int orderIndex,
        String status,
        int progressPercent,
        Instant startedAt,
        Instant completedAt
) {
    public static StudentLessonProgressResponse from(LessonProgress progress) {
        return new StudentLessonProgressResponse(
                progress.getLesson().getId(),
                progress.getLesson().getTitle(),
                progress.getLesson().getContent(),
                progress.getLesson().getSkillType().name(),
                progress.getLesson().getOrderIndex(),
                progress.getStatus().name(),
                progress.getProgressPercent(),
                progress.getStartedAt(),
                progress.getCompletedAt()
        );
    }
}

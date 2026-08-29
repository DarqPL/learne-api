package com.learne.backend.student.learning.dto;

import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.LessonProgress;
import com.learne.backend.student.learning.enums.LessonProgressStatus;
import com.learne.backend.teacher.content.entity.Topic;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record StudentEnrollmentProgressResponse(
        Long enrollmentId,
        String status,
        int progressPercent,
        long completedLessonCount,
        long totalLessonCount,
        StudentProgressCourseResponse course,
        List<StudentProgressTopicResponse> topics
) {
    public static StudentEnrollmentProgressResponse from(CourseEnrollment enrollment) {
        List<LessonProgress> orderedProgress = enrollment.getLessonProgress().stream()
                .sorted(progressOrder())
                .toList();
        Map<Topic, List<LessonProgress>> byTopic = orderedProgress.stream()
                .collect(Collectors.groupingBy(
                        progress -> progress.getLesson().getTopic(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        long completed = orderedProgress.stream()
                .filter(progress -> progress.getStatus() == LessonProgressStatus.COMPLETED)
                .count();
        return new StudentEnrollmentProgressResponse(
                enrollment.getId(),
                enrollment.getStatus().name(),
                enrollment.getProgressPercent(),
                completed,
                orderedProgress.size(),
                StudentProgressCourseResponse.from(enrollment.getCourse()),
                byTopic.entrySet().stream()
                        .map(entry -> StudentProgressTopicResponse.from(entry.getKey(), entry.getValue()))
                        .toList()
        );
    }

    private static Comparator<LessonProgress> progressOrder() {
        return Comparator
                .comparingInt((LessonProgress progress) -> progress.getLesson().getTopic().getOrderIndex())
                .thenComparing(progress -> progress.getLesson().getTopic().getId())
                .thenComparingInt(progress -> progress.getLesson().getOrderIndex())
                .thenComparing(progress -> progress.getLesson().getId());
    }
}

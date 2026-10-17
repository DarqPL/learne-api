package com.learne.backend.teacher.content.audio.dto;

import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.List;

public record CourseAudioTargetsResponse(Long courseId, Long revisionId, CourseRevisionStatus revisionStatus,
                                         List<Target> lessons) {
    public CourseAudioTargetsResponse {
        lessons = List.copyOf(lessons);
    }

    /** sourceLessonId is informational; audio mutations use lessonRevisionId in the revision namespace. */
    public record Target(Long lessonRevisionId, Long sourceLessonId, Long topicRevisionId, String topicTitle,
                         String title, SkillType skillType, int orderIndex, String audioUrl, String audioPublicId) {
    }
}

package com.learne.backend.monetization.dto;

import com.learne.backend.monetization.entity.VipCourseSelection;
import java.time.Instant;

public record VipCourseSelectionResponse(
        Long id,
        Long courseId,
        String courseName,
        String level,
        Instant selectedAt
) {
    public static VipCourseSelectionResponse from(VipCourseSelection selection) {
        return new VipCourseSelectionResponse(
                selection.getId(),
                selection.getCourse().getId(),
                selection.getCourse().getName(),
                selection.getLevel(),
                selection.getSelectedAt());
    }
}

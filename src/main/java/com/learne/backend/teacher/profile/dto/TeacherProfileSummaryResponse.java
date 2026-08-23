package com.learne.backend.teacher.profile.dto;

import com.learne.backend.teacher.profile.entity.TeacherProfile;

public record TeacherProfileSummaryResponse(
        Long teacherId,
        String fullName,
        String email,
        String avatarUrl,
        String expertise,
        int yearsOfExperience
) {
    public static TeacherProfileSummaryResponse from(TeacherProfile profile) {
        return new TeacherProfileSummaryResponse(
                profile.getUser().getId(),
                profile.getUser().getFullName(),
                profile.getUser().getEmail(),
                profile.getAvatarUrl(),
                profile.getExpertise(),
                profile.getYearsOfExperience()
        );
    }
}

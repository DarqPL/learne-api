package com.learne.backend.teacher.profile.dto;

import com.learne.backend.teacher.profile.entity.TeacherProfile;
import com.learne.backend.teacher.profile.enums.TeacherProfileStatus;

public record TeacherProfileResponse(
        Long teacherId,
        String fullName,
        String email,
        String bio,
        String expertise,
        int yearsOfExperience,
        String avatarUrl,
        String introductionVideoUrl,
        TeacherProfileStatus status,
        boolean completed
) {
    public static TeacherProfileResponse from(TeacherProfile profile) {
        return new TeacherProfileResponse(
                profile.getUser().getId(),
                profile.getUser().getFullName(),
                profile.getUser().getEmail(),
                profile.getBio(),
                profile.getExpertise(),
                profile.getYearsOfExperience(),
                profile.getAvatarUrl(),
                profile.getIntroductionVideoUrl(),
                profile.getStatus(),
                profile.isCompleted()
        );
    }
}

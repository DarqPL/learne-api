package com.learne.backend.teacher.profile;

public record TeacherProfileSummaryResponse(
        Long teacherId,
        String fullName,
        String email,
        String avatarUrl,
        String expertise,
        int yearsOfExperience
) {
    static TeacherProfileSummaryResponse from(TeacherProfile profile) {
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

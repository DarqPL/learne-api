package com.learne.backend.teacher.profile;

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
    static TeacherProfileResponse from(TeacherProfile profile) {
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
                isCompleted(profile)
        );
    }

    static boolean isCompleted(TeacherProfile profile) {
        return !profile.getBio().isBlank() && !profile.getExpertise().isBlank();
    }
}

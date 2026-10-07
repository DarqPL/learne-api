package com.learne.backend.student.profile.dto;

import com.learne.backend.student.profile.entity.StudentProfile;
import com.learne.backend.student.profile.enums.StudentCurrentLevel;
import com.learne.backend.student.profile.enums.StudentPreferredSkill;
import java.time.LocalDate;
import java.util.List;

public record StudentProfileResponse(
        Long studentId,
        String fullName,
        String email,
        String phoneNumber,
        LocalDate dateOfBirth,
        String bio,
        String learningGoal,
        StudentCurrentLevel currentLevel,
        Integer dailyLearningMinutes,
        List<StudentPreferredSkill> preferredSkills,
        String avatarUrl,
        boolean publicProfileEnabled,
        boolean showEmail,
        boolean showPhoneNumber,
        boolean showDateOfBirth
) {
    public static StudentProfileResponse from(StudentProfile profile, List<StudentPreferredSkill> preferredSkills) {
        return new StudentProfileResponse(
                profile.getUser().getId(),
                profile.getUser().getFullName(),
                profile.getUser().getEmail(),
                profile.getPhoneNumber(),
                profile.getDateOfBirth(),
                profile.getBio(),
                profile.getLearningGoal(),
                profile.getCurrentLevel(),
                profile.getDailyLearningMinutes(),
                preferredSkills,
                profile.getAvatarUrl(),
                profile.isPublicProfileEnabled(),
                profile.isShowEmail(),
                profile.isShowPhoneNumber(),
                profile.isShowDateOfBirth()
        );
    }
}

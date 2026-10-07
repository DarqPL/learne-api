package com.learne.backend.student.profile.dto;

import com.learne.backend.student.profile.entity.StudentProfile;
import com.learne.backend.student.profile.enums.StudentCurrentLevel;
import com.learne.backend.student.profile.enums.StudentPreferredSkill;
import java.time.LocalDate;
import java.util.List;

public record PublicStudentProfileResponse(
        Long studentId,
        String fullName,
        String avatarUrl,
        String bio,
        String learningGoal,
        StudentCurrentLevel currentLevel,
        List<StudentPreferredSkill> preferredSkills,
        String email,
        String phoneNumber,
        LocalDate dateOfBirth
) {
    public static PublicStudentProfileResponse from(StudentProfile profile, List<StudentPreferredSkill> preferredSkills) {
        return new PublicStudentProfileResponse(
                profile.getUser().getId(),
                profile.getUser().getFullName(),
                profile.getAvatarUrl(),
                profile.getBio(),
                profile.getLearningGoal(),
                profile.getCurrentLevel(),
                preferredSkills,
                profile.isShowEmail() ? profile.getUser().getEmail() : null,
                profile.isShowPhoneNumber() ? profile.getPhoneNumber() : null,
                profile.isShowDateOfBirth() ? profile.getDateOfBirth() : null
        );
    }
}
